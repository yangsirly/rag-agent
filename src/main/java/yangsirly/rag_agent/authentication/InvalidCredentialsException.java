package yangsirly.rag_agent.authentication;

/**
 * 登录失败时的统一业务异常。
 *
 * <p>无论是“邮箱不存在”还是“密码错误”，对外都应映射为同一提示，
 * 避免通过差异化错误信息泄露账号是否存在。</p>
 */
public class InvalidCredentialsException extends RuntimeException {

	public InvalidCredentialsException() {
		// 对外消息保持固定，不拼接邮箱或内部判断细节。
		super("Invalid account or password");
	}
}
