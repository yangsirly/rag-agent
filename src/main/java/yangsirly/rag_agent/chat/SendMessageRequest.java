package yangsirly.rag_agent.chat;

import jakarta.validation.constraints.NotBlank;

/**
 * 发送消息的 HTTP 请求体。
 *
 * <p>结构级非空用 Bean Validation；UUID 形态、正文长度等在 Service 中细化，
 * 以便统一映射为 {@code INVALID_MESSAGE_REQUEST}。</p>
 */
public record SendMessageRequest(
		@NotBlank String clientMessageId,
		@NotBlank String content) {
}
