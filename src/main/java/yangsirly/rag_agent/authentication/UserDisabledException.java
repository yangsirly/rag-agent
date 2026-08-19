package yangsirly.rag_agent.authentication;

/**
 * 账号被禁用时拒绝登录或继续访问。
 *
 * <p>与 {@link InvalidCredentialsException} 分开，便于服务端区分原因；
 * 对外 HTTP 映射时可以统一返回 401，也可以后续按产品要求调整。</p>
 */
public class UserDisabledException extends RuntimeException {

	public UserDisabledException() {
		super("User account is disabled");
	}
}
