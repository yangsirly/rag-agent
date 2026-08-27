package yangsirly.rag_agent.chat;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import yangsirly.rag_agent.common.exception.InvalidConversationRequestException;

/**
 * 会话业务边界。
 *
 * <p>
 * 工业级改造：list/rename/delete 补齐实现，删除改为"消息分批软删（各自独立事务）+
 * 会话软删"，避免 ON DELETE CASCADE 大事务，同时保持外键完整性
 * （V3 外键为 RESTRICT，V5 会话增加 deleted_at）。
 * 学习笔记：docs/learning/milestone-06-industrial-hardening.md#3.1
 * </p>
 */
@Service
public class ConversationService {

	/** 契约默认标题：请求未提供 title 时使用。 */
	static final String DEFAULT_TITLE = "新会话";

	private static final int MIN_TITLE_LENGTH = 1;
	private static final int MAX_TITLE_LENGTH = 100;

	/** 每批软删的消息行数：限制单条 UPDATE 的持锁范围。 */
	private static final int MESSAGE_DELETE_BATCH = 1000;

	private final ConversationMapper conversationMapper;
	private final MessageMapper messageMapper;
	private final Clock clock;
	private final TransactionTemplate transactionTemplate;

	public ConversationService(
			ConversationMapper conversationMapper,
			MessageMapper messageMapper,
			Clock clock,
			TransactionTemplate transactionTemplate) {
		this.conversationMapper = conversationMapper;
		this.messageMapper = messageMapper;
		this.clock = clock;
		this.transactionTemplate = transactionTemplate;
	}

	/**
	 * 创建会话。
	 *
	 * <p>
	 * 创建时同时写入 createdAt/updatedAt，确保后续列表排序稳定。
	 * </p>
	 */
	@Transactional
	public ConversationView create(CreateConversationCommand command) {
		if (command == null || command.userId() == null) {
			throw new InvalidConversationRequestException("Conversation command and userId must not be null");
		}
		ConversationEntity entity = new ConversationEntity(command.userId(), normalizeTitle(command.title()));
		LocalDateTime now = LocalDateTime.now(clock);
		entity.setCreatedAt(now);
		entity.setUpdatedAt(now);
		int rowsInserted = conversationMapper.insert(entity);
		if (rowsInserted != 1) {
			throw new IllegalStateException("Failed to insert conversation entity");
		}
		return new ConversationView(entity.getId(), entity.getTitle(), entity.getCreatedAt(), entity.getUpdatedAt());
	}

	/**
	 * 按用户归属读取单个会话。
	 */
	@Transactional(readOnly = true)
	public ConversationView getOwned(Long userId, Long conversationId) {
		ConversationEntity entity = conversationMapper.findByIdAndUserId(conversationId, userId);
		if (entity == null) {
			throw new ConversationNotFoundException();
		}
		return new ConversationView(entity.getId(), entity.getTitle(), entity.getCreatedAt(), entity.getUpdatedAt());
	}

	/**
	 * 分页查询当前用户会话列表。
	 *
	 * <p>
	 * 采用 offset 分页，并限制深分页以避免大 offset 全表扫描。
	 * </p>
	 */
	@Transactional(readOnly = true)
	public ConversationPage list(Long userId, int page, int size) {
		if (userId == null) {
			throw new InvalidConversationRequestException("userId must not be null");
		}
		if (page < 0 || size < 1 || size > 100) {
			throw new InvalidConversationRequestException("Invalid page/size");
		}
		// 深分页保护：offset 过大时全表扫描代价高，超过阈值直接拒绝并提示改用游标。
		if ((long) page * size >= 1000) {
			throw new InvalidConversationRequestException("Deep pagination denied: page*size must < 1000, use cursor");
		}
		long total = conversationMapper.countByUserId(userId);
		int totalPages = (int) Math.ceil((double) total / size);
		int offset = page * size;
		java.util.List<ConversationEntity> entities = conversationMapper.listByUserId(userId, offset, size);
		java.util.List<ConversationView> items = entities.stream()
				.map(e -> new ConversationView(e.getId(), e.getTitle(), e.getCreatedAt(), e.getUpdatedAt()))
				.toList();
		return new ConversationPage(items, page, size, total, totalPages);
	}

	/**
	 * 重命名会话并刷新 updatedAt。
	 */
	@Transactional
	public ConversationView rename(Long userId, Long conversationId, String title) {
		if (userId == null || conversationId == null) {
			throw new InvalidConversationRequestException("userId and conversationId must not be null");
		}
		ConversationEntity entity = conversationMapper.findByIdAndUserId(conversationId, userId);
		if (entity == null) {
			throw new ConversationNotFoundException();
		}
		String normalized = normalizeTitle(title);
		entity.setTitle(normalized);
		entity.setUpdatedAt(LocalDateTime.now(clock));
		int rows = conversationMapper.updateById(entity);
		if (rows != 1) {
			throw new IllegalStateException("Failed to update conversation");
		}
		return new ConversationView(entity.getId(), entity.getTitle(), entity.getCreatedAt(), entity.getUpdatedAt());
	}

	/**
	 * 删除本人会话：消息分批软删（每批一个独立事务）+ 会话软删（单独小事务）。
	 *
	 * <p>
	 * 不能用一个 @Transactional 包住整个循环——那样批处理只是"把大事务切成多条语句"，
	 * 锁仍然持有到整体提交，违背了消除 CASCADE 大事务的初衷。批次独立提交带来的中间状态
	 * （部分消息已删、会话仍在）是可接受的：消息只通过会话可见，且操作可重试幂等。
	 * 会话软删放在最后：若中途崩溃，会话仍有效，用户重试删除即可收敛。
	 * 外键保持 RESTRICT，行均保留，无孤儿数据。
	 * </p>
	 */
	public void delete(Long userId, Long conversationId) {
		if (userId == null || conversationId == null) {
			throw new InvalidConversationRequestException("userId and conversationId must not be null");
		}
		// 所有权校验：非本人或已删除的会话统一 404。
		ConversationEntity entity = conversationMapper.findByIdAndUserId(conversationId, userId);
		if (entity == null) {
			throw new ConversationNotFoundException();
		}
		LocalDateTime now = LocalDateTime.now(clock);

		// 1) 消息分批软删，每批一个独立事务，锁持有时间与批大小成正比。
		while (true) {
			Integer affected = transactionTemplate
					.execute(status -> messageMapper.softDeleteBatch(conversationId, now, MESSAGE_DELETE_BATCH));
			if (affected == null || affected < MESSAGE_DELETE_BATCH) {
				break;
			}
		}

		// 2) 会话软删：单行 UPDATE 的小事务；行保留以满足 messages 外键。
		int deleted = conversationMapper.softDeleteById(conversationId, now);
		if (deleted != 1) {
			// 影响行数为 0：并发下另一请求已抢先软删（或行已不存在），按资源不存在处理。
			throw new ConversationNotFoundException();
		}
	}

	public record ConversationView(
			Long id,
			String title,
			LocalDateTime createdAt,
			LocalDateTime updatedAt) {
	}

	public record ConversationPage(
			java.util.List<ConversationView> items,
			int page,
			int size,
			long totalElements,
			int totalPages) {
	}

	/**
	 * 统一 UTC 时间序列化格式，供 API 响应复用。
	 */
	static String formatUtc(LocalDateTime dateTime) {
		if (dateTime == null) {
			return null;
		}
		return DateTimeFormatter.ISO_INSTANT.format(dateTime.toInstant(ZoneOffset.UTC));
	}

	/**
	 * 规范化会话标题：空值回退默认标题，非空值做 trim + 长度校验。
	 */
	static String normalizeTitle(String title) {
		if (title == null || title.isBlank()) {
			return DEFAULT_TITLE;
		}
		String strippedTitle = title.strip();
		int len = strippedTitle.codePointCount(0, strippedTitle.length());
		if (len < MIN_TITLE_LENGTH || len > MAX_TITLE_LENGTH) {
			throw new InvalidConversationRequestException("Title length must be between 1 and 100 characters");
		}
		return strippedTitle;
	}

	/**
	 * 领域视图到接口响应的映射。
	 */
	static ConversationResponse toResponse(int statusCode, ConversationView view) {
		return new ConversationResponse(
				statusCode,
				view.id().toString(),
				view.title(),
				formatUtc(view.createdAt()),
				formatUtc(view.updatedAt()));
	}
}
