package yangsirly.rag_agent.authentication;

import java.util.Date;

/**
 * Access JWT 的签发结果。除了把 Token 交给浏览器，还要把 jti/过期时间
 * 写入 refresh session，登出或重放时才能立即吊销对应的访问令牌。
 */
public record IssuedAccessToken(String value, String jti, String sessionId, Date issuedAt, Date expiresAt) {
}
