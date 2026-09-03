package yangsirly.rag_agent.authentication;

import java.time.LocalDateTime;

/** 登录或刷新后写入两个 HttpOnly Cookie 的凭证集合。 */
public record TokenPair(IssuedAccessToken accessToken, String refreshToken, LocalDateTime refreshExpiresAt) {
}
