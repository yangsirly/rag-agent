package yangsirly.rag_agent.chat;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import yangsirly.rag_agent.common.exception.InvalidMessageRequestException;
import yangsirly.rag_agent.common.exception.RateLimitExceededException;
import yangsirly.rag_agent.common.ratelimit.RateLimitProperties;
import yangsirly.rag_agent.common.ratelimit.RateLimiter;

/**
 * 消息发送与历史查询的业务边界。
 *
 * <p>
 * <b>技术决策（方案 A）</b>：一次发送的"写路径"在一个事务内完成
 * 「写 USER → 写 ASSISTANT → 更新会话 updatedAt」。
 * 模板回复没有外部 IO，单事务可保证两条消息一致；
 * 接入真实模型后必须改为异步状态或拆分事务（学习笔记将展开）。
 * </p>
 *
 * <p>
 * <b>幂等不变量</b>：同一会话内一个 {@code clientMessageId}
 * 最多对应一条 USER 消息和一条 ASSISTANT 回复。
 * 最终保证是数据库唯一约束 {@code uk_messages_conversation_client_message}，
 * 不能只靠"先查后写"；Redis SET NX 只是减少重复打 DB 的快路径。
 * </p>
 *
 * <p>
 * <b>工业级事务边界</b>：{@link #send} 本身不加 @Transactional。
 * 校验、限流、Redis 快路径、幂等预查都在事务外（每条语句独立快照，
 * 能看到并发已提交的行）；只有三条写入用 {@link TransactionTemplate}
 * 包成一个事务。这样修复了两个问题：
 * 1) MySQL REPEATABLE READ 下，事务内捕获 DuplicateKeyException 后重查
 * 仍读旧快照，看不到并发已提交的 USER 消息，导致 500；
 * 2) 限流与 Redis 调用不再占用 Hikari 连接（@Transactional 在第一条 SQL
 * 前就会向连接池借连接）。
 * 学习笔记：docs/learning/milestone-06-industrial-hardening.md#3.2
 * </p>
 */
@Service
public class MessageService {

	/** 第一阶段固定模板回复，不调用真实模型。 */
	static final String TEMPLATE_REPLY = "已收到你的问题。本系统当前处于第一阶段，暂未接入真实模型。";

	private static final int MIN_CONTENT_LENGTH = 1;
	private static final int MAX_CONTENT_LENGTH = 10_000;
	private static final int MIN_PAGE_SIZE = 1;
	private static final int MAX_PAGE_SIZE = 100;
	/** 深分页保护阈值：page*size 达到该值即拒绝，引导客户端改用游标。 */
	private static final int MAX_PAGE_OFFSET = 1000;
	private static final String CLIENT_MESSAGE_UNIQUE = "uk_messages_conversation_client_message";

	private final ConversationMapper conversationMapper;
	private final MessageMapper messageMapper;
	private final Clock clock;
	private final RateLimiter rateLimiter;
	private final RateLimitProperties rateLimitProperties;
	private final StringRedisTemplate stringRedisTemplate;
	private final TransactionTemplate transactionTemplate;

	public MessageService(
			ConversationMapper conversationMapper,
			MessageMapper messageMapper,
			Clock clock,
			RateLimiter rateLimiter,
			RateLimitProperties rateLimitProperties,
			@Autowired(required = false) StringRedisTemplate stringRedisTemplate,
			TransactionTemplate transactionTemplate) {
		this.conversationMapper = conversationMapper;
		this.messageMapper = messageMapper;
		this.clock = clock;
		this.rateLimiter = rateLimiter;
		this.rateLimitProperties = rateLimitProperties;
		this.stringRedisTemplate = stringRedisTemplate;
		this.transactionTemplate = transactionTemplate;
	}

	/**
	 * 发送消息并生成模板回复。
	 *
	 * <p>
	 * 读操作（归属校验、幂等预查、Redis 快路径）在事务外执行；
	 * 写操作（两条消息 + 会话 updatedAt）在单个编程式事务内执行；
	 * 撞唯一约束时在事务回滚后用新快照重查，走"重试命中"路径。
	 * </p>
	 */
	public SendResult send(SendMessageCommand command) {
		// --- 1. 入参与字段形态 ---
		if (command == null) {
			throw new InvalidMessageRequestException("command must not be null");
		}
		Long userId = command.userId();
		Long conversationId = command.conversationId();
		String clientMessageId = command.clientMessageId();
		String content = command.content();
		if (userId == null || conversationId == null) {
			throw new InvalidMessageRequestException("userId and conversationId must not be null");
		}
		validateClientMessageId(clientMessageId);
		validateContent(content);

		// --- 2. 发送限流（user 维度，20/min 默认）。在事务外执行，不占用 DB 连接。 ---
		if (rateLimiter != null && rateLimitProperties != null) {
			String key = "send:user:" + userId;
			int limit = rateLimitProperties.sendPerUserPerMinute();
			if (!rateLimiter.tryAcquire(key, limit, Duration.ofMinutes(1))) {
				throw new RateLimitExceededException("Too many send requests", 60, limit);
			}
		}

		// --- 3. 会话所有权（独立快照，已删除或非本人 → 404） ---
		ConversationEntity conversation = conversationMapper.findByIdAndUserId(conversationId, userId);
		if (conversation == null) {
			throw new ConversationNotFoundException();
		}

		// --- 4. Redis 快路径：SET NX 标记 30s。
		// 命中（返回 false）说明 30s 内出现过相同幂等键，转 DB 查证；
		// 未命中则继续走 DB 预查。Redis 异常时静默降级——DB 唯一约束才是最终仲裁。 ---
		if (stringRedisTemplate != null) {
			String redisKey = "idmp:conv:" + conversationId + ":client:" + clientMessageId;
			try {
				Boolean ok = stringRedisTemplate.opsForValue().setIfAbsent(redisKey, "1", Duration.ofSeconds(30));
				if (Boolean.FALSE.equals(ok)) {
					MessageEntity existing = messageMapper.findUserMessageByClientMessageId(conversationId,
							clientMessageId);
					if (existing != null) {
						return resolveExistingPair(existing, content);
					}
				}
			} catch (Exception ignored) {
				// Redis 不可用不影响正确性，只损失快路径。
			}
		}

		// --- 5. 幂等预查（快速路径；并发下仍可能双双未命中，靠唯一约束兜底） ---
		MessageEntity existingUser = messageMapper.findUserMessageByClientMessageId(conversationId, clientMessageId);
		if (existingUser != null) {
			return resolveExistingPair(existingUser, content);
		}

		// --- 6. 写路径：单事务写入 USER + ASSISTANT + 会话 updatedAt ---
		LocalDateTime now = LocalDateTime.now(clock);
		MessageEntity userEntity = MessageEntity.userMessage(conversationId, clientMessageId, content);
		userEntity.setCreatedAt(now);
		MessageEntity assistant;
		try {
			assistant = transactionTemplate.execute(status -> {
				messageMapper.insert(userEntity);
				MessageEntity assistantEntity = MessageEntity.assistantReply(
						conversationId, userEntity.getId(), TEMPLATE_REPLY);
				assistantEntity.setCreatedAt(now);
				messageMapper.insert(assistantEntity);
				conversation.setUpdatedAt(now);
				conversationMapper.updateById(conversation);
				return assistantEntity;
			});
		} catch (DuplicateKeyException ex) {
			if (isClientMessageConflict(ex)) {
				// 事务已回滚，这里用新连接/新快照重查，
				// 才能看到并发胜者已提交的 USER 消息（RR 隔离级别下的关键点）。
				MessageEntity raced = messageMapper.findUserMessageByClientMessageId(conversationId, clientMessageId);
				if (raced == null) {
					throw new IllegalStateException(
							"Duplicate clientMessageId conflict but USER message was not found", ex);
				}
				return resolveExistingPair(raced, content);
			}
			throw ex;
		}

		// --- 7. 组装首次创建结果（事务正常提交） ---
		return new SendResult(true, toView(userEntity), toView(assistant));
	}

	/**
	 * 查询会话消息历史。
	 *
	 * <p>
	 * 分页语义：page=0 最新一页；页内 items 按 createdAt ASC, id ASC。
	 * 默认 size=50，最大 100；page*size >= 1000 拒绝深分页。
	 * 传 cursor（消息 id）时改为游标分页，返回该消息之后的 size 条（时间正序）。
	 * </p>
	 */
	@Transactional(readOnly = true)
	public MessagePage listMessages(Long userId, Long conversationId, int page, int size, Long cursor) {
		if (userId == null || conversationId == null) {
			throw new InvalidMessageRequestException("userId and conversationId must not be null");
		}
		if (page < 0) {
			throw new InvalidMessageRequestException("page must be >= 0");
		}
		if (size < MIN_PAGE_SIZE || size > MAX_PAGE_SIZE) {
			throw new InvalidMessageRequestException("size must be between " + MIN_PAGE_SIZE + " and " + MAX_PAGE_SIZE);
		}
		if (cursor == null && (long) page * size >= MAX_PAGE_OFFSET) {
			throw new InvalidMessageRequestException(
					"Deep pagination denied: page*size must < " + MAX_PAGE_OFFSET + ", use cursor");
		}
		ConversationEntity conversation = conversationMapper.findByIdAndUserId(conversationId, userId);
		if (conversation == null) {
			throw new ConversationNotFoundException();
		}
		long totalElements = messageMapper.countByConversationId(conversationId);
		List<MessageEntity> pageItems;
		if (cursor != null) {
			// 游标分页：返回 cursor 消息之后（更晚）的 size 条，时间正序。
			pageItems = messageMapper.pageAfterCursor(conversationId, cursor, size);
		} else {
			// 最新页优先：SQL 取第 page 页（时间倒序），内存反转为页内正序。
			List<MessageEntity> newestFirst = messageMapper.pageNewestFirst(conversationId, page, size);
			List<MessageEntity> tmp = new ArrayList<>(newestFirst);
			Collections.reverse(tmp);
			pageItems = tmp;
		}
		List<MessageView> items = pageItems.stream().map(MessageService::toView).toList();
		int totalPages = totalElements == 0 ? 0 : (int) ((totalElements + size - 1) / size);
		return new MessagePage(items, page, size, totalElements, totalPages);
	}

	/**
	 * 幂等命中：已有 USER 消息时校验 content 并加载 ASSISTANT。
	 *
	 * @return created=false 的结果
	 */
	private SendResult resolveExistingPair(MessageEntity existingUser, String requestedContent) {
		// content 与首次必须完全一致（原始串 equals，不先 strip）
		if (!existingUser.getContent().equals(requestedContent)) {
			throw new IdempotencyConflictException();
		}
		MessageEntity assistant = messageMapper.findAssistantByReplyTo(existingUser.getId());
		// 理论上不应出现；单事务保证成对
		if (assistant == null) {
			throw new IllegalStateException("USER message without ASSISTANT reply");
		}
		return new SendResult(false, toView(existingUser), toView(assistant));
	}

	/**
	 * 判断 DuplicateKeyException 是否由幂等唯一约束触发。
	 * MySQL/H2 的异常消息里会带上约束名，沿 cause 链逐层查找。
	 */
	private boolean isClientMessageConflict(DuplicateKeyException exception) {
		String normalizedConstraintName = CLIENT_MESSAGE_UNIQUE.toLowerCase(Locale.ROOT);
		Throwable current = exception;
		while (current != null) {
			String message = current.getMessage();
			if (message != null && message.toLowerCase(Locale.ROOT).contains(normalizedConstraintName)) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	/**
	 * 校验幂等键格式：必须是非空 UUID 字符串。
	 */
	static void validateClientMessageId(String clientMessageId) {
		if (clientMessageId == null || clientMessageId.isBlank()) {
			throw new InvalidMessageRequestException("clientMessageId must not be null or blank");
		}
		try {
			UUID.fromString(clientMessageId);
		} catch (IllegalArgumentException ex) {
			throw new InvalidMessageRequestException("clientMessageId must be a valid UUID string", ex);
		}
	}

	/**
	 * 校验消息文本内容（去空白后非空，长度 1~10000 code points）。
	 */
	static void validateContent(String content) {
		if (content == null || content.strip().isEmpty()) {
			throw new InvalidMessageRequestException("Content must not be null or blank");
		}
		int length = content.codePointCount(0, content.length());
		if (length < MIN_CONTENT_LENGTH || length > MAX_CONTENT_LENGTH) {
			throw new InvalidMessageRequestException(
					"Content length must be between " + MIN_CONTENT_LENGTH + " and " + MAX_CONTENT_LENGTH
							+ " code points");
		}
	}

	/**
	 * 将持久化实体映射为对外响应视图。
	 *
	 * <p>
	 * 这里会按角色约束字段：USER 必须有 clientMessageId，ASSISTANT 必须有 replyToMessageId。
	 * </p>
	 */
	static MessageView toView(MessageEntity entity) {
		if (entity == null) {
			throw new IllegalArgumentException("entity must not be null");
		}
		MessageRole role = entity.getRole();
		if (role == null) {
			throw new IllegalArgumentException("message role must not be null");
		}
		String clientMessageId = null;
		String replyToMessageId = null;
		switch (role) {
			case USER -> clientMessageId = entity.getClientMessageId();
			case ASSISTANT -> {
				Long replyTo = entity.getReplyToMessageId();
				if (replyTo == null) {
					throw new IllegalStateException("ASSISTANT message missing replyToMessageId");
				}
				replyToMessageId = replyTo.toString();
			}
		}
		return new MessageView(
				entity.getId().toString(),
				entity.getConversationId().toString(),
				clientMessageId,
				replyToMessageId,
				role.name(),
				entity.getContent(),
				ConversationService.formatUtc(entity.getCreatedAt()));
	}

	public record SendResult(
			boolean created,
			MessageView userMessage,
			MessageView assistantMessage) {
		/**
		 * 首次创建返回 201，幂等命中返回 200。
		 */
		public HttpStatus httpStatus() {
			return created ? HttpStatus.CREATED : HttpStatus.OK;
		}

		/**
		 * 转换为接口层响应 DTO。
		 */
		public SendMessageResponse toResponse() {
			return new SendMessageResponse(
					httpStatus().value(),
					userMessage,
					assistantMessage);
		}
	}

	public record MessagePage(
			List<MessageView> items,
			int page,
			int size,
			long totalElements,
			int totalPages) {
		/**
		 * 转换为分页响应 DTO。
		 */
		public MessageListResponse toResponse() {
			return new MessageListResponse(
					HttpStatus.OK.value(),
					items,
					page,
					size,
					totalElements,
					totalPages);
		}
	}
}
