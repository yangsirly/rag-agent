package yangsirly.rag_agent.common.exception;

/**
 * 会话请求参数非法，对应 400 INVALID_CONVERSATION_REQUEST。
 * 替代原先 IllegalArgumentException 字符串分流的临时方案。
 */
public class InvalidConversationRequestException extends IllegalArgumentException {
    public InvalidConversationRequestException(String message) { super(message); }
    public InvalidConversationRequestException(String message, Throwable cause) { super(message, cause); }
}
