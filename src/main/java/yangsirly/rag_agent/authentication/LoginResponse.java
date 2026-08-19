package yangsirly.rag_agent.authentication;

/**
 * 登录成功时返回给客户端的数据结构。
 *
 * <p>真正的登录凭证放在 HttpOnly Cookie 中，响应体不返回 token 明文，
 * 降低 XSS 场景下前端脚本直接读到凭证的风险。</p>
 *
 * @param statusCode 业务响应体中的状态码；真正的 HTTP 状态码由 ResponseEntity 设置
 * @param role 当前用户角色，便于前端决定展示客户或编辑者界面
 */
public record LoginResponse(int statusCode, String role) {
}
