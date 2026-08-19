package yangsirly.rag_agent.chat;

/**
 * 单条消息在 API 中的视图。
 *
 * <p>USER 与 ASSISTANT 共用结构：
 * USER 带 {@code clientMessageId}，ASSISTANT 带 {@code replyToMessageId}；
 * 不需要的字段为 null，JSON 序列化时通常会输出 null 或由全局策略省略。</p>
 */
public record MessageView(
		String id,
		String conversationId,
		String clientMessageId,
		String replyToMessageId,
		String role,
		String content,
		String createdAt) {
}
