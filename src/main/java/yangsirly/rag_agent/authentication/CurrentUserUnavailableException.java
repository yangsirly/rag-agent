package yangsirly.rag_agent.authentication;

/**
 * {@code GET /me} 无法给出可用当前用户时抛出。
 *
 * <p>
 * 典型场景：SecurityContext 中无主体、用户已被删除、或账号 DISABLED。
 * 对外统一映射为 401 {@code UNAUTHORIZED}，不区分具体原因，
 * 避免通过差异化错误泄露账号是否仍存在。
 * </p>
 *
 * <p>
 * 与 {@link InvalidAccessTokenException} 的边界：
 * token 解析失败发生在过滤器层，用后者；
 * 已进 Controller / Service 后的“当前用户不可用”用本异常。
 * </p>
 */
public class CurrentUserUnavailableException extends RuntimeException {

	public CurrentUserUnavailableException() {
		// 对外消息固定，不拼接 userId 或内部判断细节
		super("Current user is unavailable");
	}
}
