package yangsirly.rag_agent.authentication;

import jakarta.validation.constraints.NotBlank;

/**
 * 接收登录接口 JSON 请求体的数据结构。
 *
 * <p>数据来自外部客户端，属于不可信输入。当前骨干阶段只校验字段非空；
 * 邮箱规范化和密码业务规则将在后续实现核心登录逻辑时补齐。</p>
 *
 * @param email 用户提交的登录邮箱
 * @param password 用户提交的明文密码，只能用于本次登录校验，不得写入日志
 */
public record LoginRequest(
		// @NotBlank 同时拒绝 null、空字符串和只包含空白字符的字符串。
		@NotBlank String email,
		@NotBlank String password) {
}
