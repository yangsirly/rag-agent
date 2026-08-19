package yangsirly.rag_agent.chat;

/**
 * 会话资源的成功响应。
 *
 * <p>id 以十进制字符串返回，避免 JavaScript 超过 2^53-1 后精度丢失。
 * 时间字段使用 ISO-8601 字符串，与契约一致。</p>
 */
public record ConversationResponse(
		int statusCode,
		String id,
		String title,
		String createdAt,
		String updatedAt) {
}
