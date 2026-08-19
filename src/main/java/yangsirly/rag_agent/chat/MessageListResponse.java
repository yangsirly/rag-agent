package yangsirly.rag_agent.chat;

import java.util.List;

/**
 * 消息历史分页响应。
 *
 * <p>第一阶段使用简单 offset 分页；消息列表默认 size=50。
 * page=0 表示最新一页，items 内部仍按时间正序。</p>
 */
public record MessageListResponse(
		int statusCode,
		List<MessageView> items,
		int page,
		int size,
		long totalElements,
		int totalPages) {
}
