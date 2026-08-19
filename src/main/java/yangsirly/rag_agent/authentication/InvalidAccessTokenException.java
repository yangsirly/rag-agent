package yangsirly.rag_agent.authentication;

/**
 * Access Token 无效、过期或 claims 不完整时抛出。
 *
 * <p>过滤器捕获后清除 SecurityContext，不向客户端暴露具体失败原因。</p>
 */
public class InvalidAccessTokenException extends RuntimeException {

	public InvalidAccessTokenException(String message) {
		super(message);
	}

	public InvalidAccessTokenException(String message, Throwable cause) {
		super(message, cause);
	}
}
