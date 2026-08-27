package yangsirly.rag_agent.common.exception;

/**
 * 消息请求参数非法，对应 400 INVALID_MESSAGE_REQUEST。
 */
public class InvalidMessageRequestException extends IllegalArgumentException {
    public InvalidMessageRequestException(String message) { super(message); }
    public InvalidMessageRequestException(String message, Throwable cause) { super(message, cause); }
}
