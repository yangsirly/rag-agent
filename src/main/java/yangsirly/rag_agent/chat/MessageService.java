package yangsirly.rag_agent.chat;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 消息发送与历史查询的业务边界——里程碑 3 的核心闭环。
 *
 * <p>
 * <b>技术决策（方案 A）</b>：一次发送在同一事务内完成
 * 「校验 → 写 USER → 写 ASSISTANT → 更新会话 updatedAt」。
 * 模板回复没有外部 IO，单事务可保证两条消息一致；
 * 接入真实模型后必须改为异步状态或拆分事务（学习笔记将展开）。
 * </p>
 *
 * <p>
 * <b>幂等不变量</b>：同一会话内一个 {@code clientMessageId}
 * 最多对应一条 USER 消息和一条 ASSISTANT 回复。
 * 最终保证是数据库唯一约束 {@code uk_messages_conversation_client_message}，
 * 不能只靠“先查后写”。
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
	private static final String CLIENT_MESSAGE_UNIQUE = "uk_messages_conversation_client_message";

	private final ConversationMapper conversationMapper;
	private final MessageMapper messageMapper;
	private final Clock clock;

	public MessageService(
			ConversationMapper conversationMapper,
			MessageMapper messageMapper,
			Clock clock) {
		this.conversationMapper = conversationMapper;
		this.messageMapper = messageMapper;
		this.clock = clock;
	}

	/**
	 * 发送消息并生成模板回复（单事务）。
	 *
	 * <p>
	 * 目标流程（实现时严格按序）：
	 * <ol>
	 * <li>校验 conversationId / clientMessageId / content；</li>
	 * <li>{@code findByIdAndUserId} 确认会话归属，失败 →
	 * {@link ConversationNotFoundException}；</li>
	 * <li>按 clientMessageId 查是否已有 USER 消息：
	 * <ul>
	 * <li>存在且 content 相同 → 加载配对 ASSISTANT，返回 {@code created=false}（HTTP 200）；</li>
	 * <li>存在且 content 不同 → {@link IdempotencyConflictException}；</li>
	 * </ul>
	 * </li>
	 * <li>插入 USER 消息；若并发撞唯一约束，捕获 {@link DuplicateKeyException} 后走“重试命中”路径；</li>
	 * <li>插入指向该 USER 的 ASSISTANT 模板消息；</li>
	 * <li>更新会话 {@code updatedAt}；</li>
	 * <li>返回 {@code created=true}（HTTP 201）及两条消息视图。</li>
	 * </ol>
	 * </p>
	 *
	 * @param command 发送命令（userId 必须来自认证主体）
	 * @return 消息对 + 是否首次创建
	 */
	@Transactional
	public SendResult send(SendMessageCommand command) {
		// --- 1. 入参与字段形态 ---
		if (command == null) {
			throw new IllegalArgumentException("command must not be null");
		}
		Long userId = command.userId();
		Long conversationId = command.conversationId();
		String clientMessageId = command.clientMessageId();
		String content = command.content();
		if (userId == null || conversationId == null) {
			throw new IllegalArgumentException("userId and conversationId must not be null");
		}
		validateClientMessageId(clientMessageId);
		validateContent(content);

		// --- 2. 会话所有权 ---
		ConversationEntity conversation = conversationMapper.findByIdAndUserId(conversationId, userId);
		if (conversation == null) {
			throw new ConversationNotFoundException();
		}

		// --- 3. 幂等预查（快速路径；并发下仍可能双双未命中，靠唯一约束兜底）---
		MessageEntity existingUser = messageMapper.findUserMessageByClientMessageId(conversationId, clientMessageId);
		if (existingUser != null) {
			return resolveExistingPair(existingUser, content);
		}

		// --- 4. 首次写入 USER ---
		LocalDateTime now = LocalDateTime.now(clock);
		MessageEntity userEntity = MessageEntity.userMessage(conversationId, clientMessageId, content);
		userEntity.setCreatedAt(now);
		try {
			messageMapper.insert(userEntity);
		}
		catch (DuplicateKeyException ex) {
			if (isClientMessageConflict(ex)) {
				// 并发下另一事务已提交：再查并走 resolveExistingPair
				MessageEntity raced = messageMapper.findUserMessageByClientMessageId(conversationId, clientMessageId);
				if (raced == null) {
					throw new IllegalStateException(
							"Duplicate clientMessageId conflict but USER message was not found", ex);
				}
				return resolveExistingPair(raced, content);
			}
			throw ex;
		}

		// --- 5. 写入 ASSISTANT 模板回复 ---
		MessageEntity assistantEntity = MessageEntity.assistantReply(
				conversationId, userEntity.getId(), TEMPLATE_REPLY);
		assistantEntity.setCreatedAt(now);
		messageMapper.insert(assistantEntity);

		// --- 6. 刷新会话活跃时间 ---
		conversation.setUpdatedAt(now);
		conversationMapper.updateById(conversation);

		// --- 7. 组装首次创建结果 ---
		return new SendResult(true, toView(userEntity), toView(assistantEntity));
	}

	/**
	 * 查询会话消息历史。
	 *
	 * <p>
	 * 分页语义：page=0 最新一页；页内 items 按 createdAt ASC, id ASC。
	 * 默认 size=50，最大 100（与契约一致）。
	 * </p>
	 */
	@Transactional(readOnly = true)
	public MessagePage listMessages(Long userId, Long conversationId, int page, int size) {
		// 校验 page >= 0；size 在 [1, 100]；非法 → IllegalArgumentException → INVALID_MESSAGE_REQUEST
		if (userId == null || conversationId == null) {
			throw new IllegalArgumentException("userId and conversationId must not be null");
		}
		if (page < 0) {
			throw new IllegalArgumentException("page must be >= 0");
		}
		if (size < MIN_PAGE_SIZE || size > MAX_PAGE_SIZE) {
			throw new IllegalArgumentException(
					"size must be between " + MIN_PAGE_SIZE + " and " + MAX_PAGE_SIZE);
		}

		// 所有权：findByIdAndUserId；null → ConversationNotFoundException
		ConversationEntity conversation = conversationMapper.findByIdAndUserId(conversationId, userId);
		if (conversation == null) {
			throw new ConversationNotFoundException();
		}

		// total = countByConversationId
		long totalElements = messageMapper.countByConversationId(conversationId);

		// 查询“最新页优先”的一页，再在内存中保证页内正序（createdAt ASC, id ASC）
		List<MessageEntity> newestFirst = messageMapper.pageNewestFirst(conversationId, page, size);
		List<MessageEntity> pageItems = new ArrayList<>(newestFirst);
		Collections.reverse(pageItems);

		// 映射 MessageView 列表，计算 totalPages
		List<MessageView> items = pageItems.stream().map(MessageService::toView).toList();
		int totalPages = totalElements == 0 ? 0 : (int) ((totalElements + size - 1) / size);
		return new MessagePage(items, page, size, totalElements, totalPages);
	}

	// -------------------------------------------------------------------------
	// 实现时拆出的辅助步骤（骨架预留，避免 send 方法膨胀）
	// -------------------------------------------------------------------------

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

	/** 判断唯一约束冲突是否来自 client_message_id。 */
	private boolean isClientMessageConflict(DuplicateKeyException exception) {
		// 沿异常链查找约束名 CLIENT_MESSAGE_UNIQUE（与注册模块 containsConstraintName 同模式）
		String normalizedConstraintName = CLIENT_MESSAGE_UNIQUE.toLowerCase(Locale.ROOT);
		Throwable current = exception;
		while (current != null) {
			// MySQL 和 H2 驱动都会在异常链中包含违反的约束名。
			String message = current.getMessage();
			if (message != null && message.toLowerCase(Locale.ROOT).contains(normalizedConstraintName)) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	/** 校验 clientMessageId 为标准 UUID。 */
	static void validateClientMessageId(String clientMessageId) {
		if (clientMessageId == null || clientMessageId.isBlank()) {
			throw new IllegalArgumentException("clientMessageId must not be null or blank");
		}
		try {
			UUID.fromString(clientMessageId);
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException("clientMessageId must be a valid UUID string", ex);
		}
	}

	/**
	 * 校验消息正文长度。
	 *
	 * <p>
	 * 纯空白用 strip 后是否为空判断；持久化仍保存用户原始 content（含首尾空白）。
	 * </p>
	 */
	static void validateContent(String content) {
		if (content == null || content.strip().isEmpty()) {
			throw new IllegalArgumentException("Content must not be null or blank");
		}
		int length = content.codePointCount(0, content.length());
		if (length < MIN_CONTENT_LENGTH || length > MAX_CONTENT_LENGTH) {
			throw new IllegalArgumentException("Content length must be between " + MIN_CONTENT_LENGTH + " and "
					+ MAX_CONTENT_LENGTH + " code points");
		}
	}

	/** 实体 → API 视图；id 字段在 Controller/此处统一 String 化。 */
	static MessageView toView(MessageEntity entity) {
		// role.name()；时间用 ConversationService.formatUtc
		// USER 填 clientMessageId；ASSISTANT 填 replyToMessageId 字符串
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

	/**
	 * 发送结果。
	 *
	 * @param created          true=首次写入（HTTP 201），false=幂等重试（HTTP 200）
	 * @param userMessage      用户消息视图
	 * @param assistantMessage 模板回复视图
	 */
	public record SendResult(
			boolean created,
			MessageView userMessage,
			MessageView assistantMessage) {

		/** 按是否首次创建选择 HTTP 状态。 */
		public HttpStatus httpStatus() {
			return created ? HttpStatus.CREATED : HttpStatus.OK;
		}

		public SendMessageResponse toResponse() {
			return new SendMessageResponse(
					httpStatus().value(),
					userMessage,
					assistantMessage);
		}
	}

	/**
	 * 消息历史分页内部结果。
	 */
	public record MessagePage(
			java.util.List<MessageView> items,
			int page,
			int size,
			long totalElements,
			int totalPages) {

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

	/** 供后续实现与测试读取时钟。 */
	LocalDateTime now() {
		return LocalDateTime.now(clock);
	}

	/** 暴露模板常量，便于测试断言文案。 */
	public static String templateReply() {
		return TEMPLATE_REPLY;
	}

	/** 骨架阶段保留 UUID 解析提示，避免实现时忘记标准格式。 */
	@SuppressWarnings("unused")
	private static UUID parseUuidOrNull(String value) {
		try {
			return UUID.fromString(value);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}
}
