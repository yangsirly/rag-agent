package yangsirly.rag_agent.registration;

/**
 * Controller 交给业务层的注册命令。
 *
 * <p>它和 RegisterRequest 目前字段相同，但职责不同：Request 表示外部 HTTP 输入，
 * Command 表示已经通过 Web 层基础校验、准备交给业务流程处理的数据。</p>
 *
 * @param email 待注册邮箱
 * @param password 待校验并哈希的原始密码，使用后不应继续保留或输出
 */
public record RegisterCommand(
		String email,
		String password) {
}
