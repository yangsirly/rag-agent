package yangsirly.rag_agent.authentication;

/**
 * 退出登录成功时返回给客户端的数据结构。
 *
 * @param statusCode 业务响应体中的状态码；真正的 HTTP 状态码由 ResponseEntity 设置
 */
public record LogoutResponse(int statusCode) {
}
