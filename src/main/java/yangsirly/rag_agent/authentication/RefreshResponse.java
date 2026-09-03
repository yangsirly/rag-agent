package yangsirly.rag_agent.authentication;

/** 刷新成功时的稳定响应契约；两个新凭证只通过 HttpOnly Cookie 下发。 */
public record RefreshResponse(int statusCode) {
}
