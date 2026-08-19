package yangsirly.rag_agent.authentication;

/**
 * 当前登录用户信息，供前端启动/刷新时恢复登录态。
 *
 * <p>{@code userId} 在 JSON 中为十进制字符串，避免前端 Number 精度问题。
 * 真正的凭证仍在 HttpOnly Cookie 中，本响应不返回 token。</p>
 *
 * @param statusCode 业务响应体中的状态码；真正的 HTTP 状态码由 ResponseEntity 设置
 * @param userId     用户主键（十进制字符串）
 * @param email      规范化后的邮箱
 * @param role       角色枚举名，如 CUSTOMER / EDITOR
 */
public record MeResponse(int statusCode, String userId, String email, String role) {
}
