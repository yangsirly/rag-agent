package yangsirly.rag_agent.chat;

/**
 * 会话不存在，或当前用户无权访问（对外统一伪装为不存在）。
 *
 * <p>映射为 HTTP 404 + {@code NOT_FOUND}，避免向调用方泄露“该 id 是否属于他人”。</p>
 */
public class ConversationNotFoundException extends RuntimeException {

	public ConversationNotFoundException() {
		super("Conversation not found");
	}
}
