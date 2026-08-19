package yangsirly.rag_agent.chat;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 会话业务边界。
 *
 * <p>
 * 里程碑 3 最小闭环：创建会话（供发送消息前使用）。
 * 列表 / 改标题 / 删除属于里程碑 4，这里只保留方法签名与 TODO，避免前端契约路径完全空缺时无入口。
 * </p>
 *
 * <p>
 * 学习笔记（后续补充）：所有权查询、物理删除与外键级联。
 * </p>
 */
@Service
public class ConversationService {

	/** 契约默认标题：请求未提供 title 时使用。 */
	static final String DEFAULT_TITLE = "新会话";

	private static final int MIN_TITLE_LENGTH = 1;
	private static final int MAX_TITLE_LENGTH = 100;

	private final ConversationMapper conversationMapper;
	private final Clock clock;

	/**
	 * @param conversationMapper 会话持久化
	 * @param clock              时间来源；注入 {@link Clock} 便于测试固定时间，而不是直接调用
	 *                           {@code LocalDateTime.now()}
	 */
	public ConversationService(ConversationMapper conversationMapper, Clock clock) {
		this.conversationMapper = conversationMapper;
		this.clock = clock;
	}

	/**
	 * 为当前用户创建会话。
	 *
	 * <p>
	 * 成功路径：
	 * <ol>
	 * <li>规范化并校验标题（缺省 → 默认标题）；</li>
	 * <li>插入 conversations 行，userId 来自已认证主体；</li>
	 * <li>返回可序列化为 {@link ConversationResponse} 的视图数据。</li>
	 * </ol>
	 * </p>
	 */
	@Transactional
	public ConversationView create(CreateConversationCommand command) {
		if (command == null || command.userId() == null) {
			throw new IllegalArgumentException("Conversation command and userId must not be null");
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
	 * 按 id 读取当前用户可见的会话；不可见统一视为不存在。
	 */
	@Transactional(readOnly = true)
	public ConversationView getOwned(Long userId, Long conversationId) {
		ConversationEntity entity = conversationMapper.findByIdAndUserId(conversationId, userId);
		if (entity == null) {
			throw new ConversationNotFoundException();
		}
		return new ConversationView(entity.getId(), entity.getTitle(), entity.getCreatedAt(), entity.getUpdatedAt());
	}

	// -------------------------------------------------------------------------
	// 以下为里程碑 4 入口占位，避免 Controller 契约路径悬空；实现时删除 throw。
	// -------------------------------------------------------------------------

	/** 当前用户的会话列表（updatedAt DESC, id DESC）。 */
	@Transactional(readOnly = true)
	public ConversationPage list(Long userId, int page, int size) {
		// TODO(里程碑 4)：校验 page/size；查询分页 + total；组装 ConversationPage
		throw new UnsupportedOperationException("TODO: implement ConversationService.list (milestone 4)");
	}

	/** 修改本人会话标题。 */
	@Transactional
	public ConversationView rename(Long userId, Long conversationId, String title) {
		// TODO(里程碑 4)：所有权校验 + 标题校验 + 更新 + 返回最新视图
		throw new UnsupportedOperationException("TODO: implement ConversationService.rename (milestone 4)");
	}

	/**
	 * 物理删除本人会话；消息由外键 ON DELETE CASCADE 一并删除。
	 *
	 * <p>
	 * 首次删除成功；再次删除同一 id → {@link ConversationNotFoundException}。
	 * </p>
	 */
	@Transactional
	public void delete(Long userId, Long conversationId) {
		// TODO(里程碑 4)：findByIdAndUserId；null → not found；deleteById；断言删除 1 行
		throw new UnsupportedOperationException("TODO: implement ConversationService.delete (milestone 4)");
	}

	// -------------------------------------------------------------------------
	// 内部视图：与 HTTP DTO 解耦，Controller 负责 statusCode 与字符串化
	// -------------------------------------------------------------------------

	/**
	 * 会话在业务层的只读视图。
	 *
	 * @param id        主键
	 * @param title     标题
	 * @param createdAt 创建时间（UTC 语义由序列化统一）
	 * @param updatedAt 更新时间
	 */
	public record ConversationView(
			Long id,
			String title,
			LocalDateTime createdAt,
			LocalDateTime updatedAt) {
	}

	/** 列表分页内部结果（里程碑 4）。 */
	public record ConversationPage(
			java.util.List<ConversationView> items,
			int page,
			int size,
			long totalElements,
			int totalPages) {
	}

	/**
	 * 将实体时间格式化为契约要求的 ISO-8601 UTC 字符串。
	 *
	 * <p>
	 * 骨架阶段提供工具方法，实现 create/get 时复用，避免 Controller 各自格式化。
	 * </p>
	 */
	static String formatUtc(LocalDateTime dateTime) {
		if (dateTime == null) {
			return null;
		}
		return DateTimeFormatter.ISO_INSTANT.format(dateTime.toInstant(ZoneOffset.UTC));
	}

	/**
	 * 标题规范化骨架：实现 create/rename 时调用。
	 *
	 * @throws IllegalArgumentException 标题非法时，由异常处理器映射为 INVALID_CONVERSATION_REQUEST
	 */
	static String normalizeTitle(String title) {
		if (title == null || title.isBlank()) {
			return DEFAULT_TITLE;
		}
		String strippedTitle = title.strip();
		int len = strippedTitle.codePointCount(0, strippedTitle.length());
		if (len < MIN_TITLE_LENGTH || len > MAX_TITLE_LENGTH) {
			throw new IllegalArgumentException("Title length must be between 1 and 100 characters");
		}
		return strippedTitle;
	}

	/** 供测试或后续实现读取当前时钟。 */
	LocalDateTime now() {
		return LocalDateTime.now(clock);
	}

	/** 组装 HTTP 成功响应（Controller 使用）。 */
	static ConversationResponse toResponse(int statusCode, ConversationView view) {
		return new ConversationResponse(
				statusCode,
				String.valueOf(view.id()),
				view.title(),
				formatUtc(view.createdAt()),
				formatUtc(view.updatedAt()));
	}

	/** 供异常处理器等引用默认标题常量时保持包内一致。 */
	static HttpStatus createdStatus() {
		return HttpStatus.CREATED;
	}
}
