package yangsirly.rag_agent.chat;

/**
 * 发送消息成功响应：一次返回 USER + ASSISTANT 消息对。
 *
 * <p>{@code statusCode} 区分首次写入（201）与幂等重试命中（200）。
 * Controller 同时用该值设置 HTTP 状态码，与响应体保持一致。</p>
 */
public record SendMessageResponse(
		int statusCode,
		MessageView userMessage,
		MessageView assistantMessage) {
}
