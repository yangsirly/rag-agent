package yangsirly.rag_agent.chat;

/**
 * 同一会话内复用了已存在的 {@code clientMessageId}，但消息内容与首次不一致。
 *
 * <p>幂等重试要求 content 与首次完全一致；内容不同说明客户端误复用了幂等键，
 * 映射为 HTTP 409 + {@code IDEMPOTENCY_CONFLICT}，不得静默覆盖首次内容。</p>
 */
public class IdempotencyConflictException extends RuntimeException {

	public IdempotencyConflictException() {
		super("clientMessageId already used with different content");
	}
}
