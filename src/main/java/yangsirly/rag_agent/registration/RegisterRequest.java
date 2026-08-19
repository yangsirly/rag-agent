package yangsirly.rag_agent.registration;

import jakarta.validation.constraints.NotBlank;

/**
 * 接收注册接口 JSON 请求体的数据结构。
 *
 * <p>请求中的 {@code email} 和 {@code password} 字段会按名称绑定到 record 组件。
 * 这里的数据来自外部客户端，属于不可信输入，必须先校验再进入业务层。</p>
 *
 * @param email 用户提交的邮箱
 * @param password 用户提交的明文密码，只能用于本次注册处理，不应记录到日志或直接存储
 */
public record RegisterRequest(
		// @NotBlank 同时拒绝 null、空字符串和只包含空白字符的字符串。
		@NotBlank String email,
		@NotBlank String password) {
}
