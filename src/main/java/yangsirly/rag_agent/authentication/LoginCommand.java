package yangsirly.rag_agent.authentication;

/**
 * Controller 交给认证业务层的登录命令。
 *
 * <p>与 {@link LoginRequest} 字段相同，但职责不同：Request 表示外部 HTTP 输入，
 * Command 表示已通过 Web 层基础校验、准备进入登录流程的数据。</p>
 *
 * @param email 待登录邮箱
 * @param password 待校验的明文密码，使用后不应继续保留或输出
 */
public record LoginCommand(
		String email,
		String password) {
}
