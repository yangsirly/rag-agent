package yangsirly.rag_agent.authentication;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import yangsirly.rag_agent.registration.User;

/**
 * 工业级 JWT 实现：新增 jti 用于黑名单与幂等。
 * 学习笔记：docs/learning/milestone-06-industrial-hardening.md#3.3
 */
@Service
public class JwtTokenServiceImpl implements JwtTokenService {

    static final String CLAIM_EMAIL = "email";
    static final String CLAIM_ROLE = "role";
    static final String CLAIM_STATUS = "status";
    static final String CLAIM_JTI = "jti";

    /**
     * 构造 JWT 服务。
     */
    private final AuthProperties authProperties;

    public JwtTokenServiceImpl(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    /**
     * 签发访问令牌并写入标准 claims + 业务 claims。
     *
     * <p>
     * jti 每次随机生成，供 logout 黑名单与幂等审计使用。
     * </p>
     */
    @Override
    public String issueAccessToken(AuthenticatedUser user) {
        if (user == null || user.userId() == null) {
            throw new IllegalArgumentException("Authenticated user and userId must not be null");
        }
        Date issuedAt = new Date();
        Date expiresAt = new Date(issuedAt.getTime() + authProperties.jwt().accessTokenTtlSeconds() * 1000L);
        String jti = UUID.randomUUID().toString();
        return Jwts.builder()
                .subject(String.valueOf(user.userId()))
                .issuer(authProperties.jwt().issuer())
                .issuedAt(issuedAt)
                .expiration(expiresAt)
                .id(jti)
                .claim(CLAIM_EMAIL, user.email())
                .claim(CLAIM_ROLE, user.role().name())
                .claim(CLAIM_STATUS, user.status().name())
                .claim(CLAIM_JTI, jti)
                .signWith(signingKey())
                .compact();
    }

    /**
     * 解析并验证访问令牌。
     *
     * <p>
     * 校验签名、issuer、过期时间；解析失败统一映射为
     * InvalidAccessTokenException。
     * </p>
     */
    @Override
    public AuthenticatedUser parseAccessToken(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidAccessTokenException("Access token must not be blank");
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey())
                    .requireIssuer(authProperties.jwt().issuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Long userId = Long.valueOf(claims.getSubject());
            String email = claims.get(CLAIM_EMAIL, String.class);
            String roleName = claims.get(CLAIM_ROLE, String.class);
            String statusName = claims.get(CLAIM_STATUS, String.class);
            String jti = claims.getId();
            if (jti == null) {
                jti = claims.get(CLAIM_JTI, String.class);
            }
            if (email == null || roleName == null || statusName == null || jti == null) {
                throw new InvalidAccessTokenException("Access token claims are incomplete");
            }
            return new AuthenticatedUser(userId, email, User.Role.valueOf(roleName), User.Status.valueOf(statusName));
        } catch (JwtException | IllegalArgumentException exception) {
            throw new InvalidAccessTokenException("Access token is invalid or expired", exception);
        }
    }

    /**
     * 提取 jti，供黑名单检查使用。
     *
     * <p>
     * 失败返回 null，调用方按未携带 jti 处理。
     * </p>
     */
    @Override
    public String extractJti(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey())
                    .requireIssuer(authProperties.jwt().issuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String jti = claims.getId();
            if (jti == null) {
                jti = claims.get(CLAIM_JTI, String.class);
            }
            return jti;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 提取过期时间，供 logout 计算黑名单 TTL。
     */
    @Override
    public Date extractExpiration(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey())
                    .requireIssuer(authProperties.jwt().issuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return claims.getExpiration();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 基于配置密钥生成 HMAC 签名 key。
     */
    private SecretKey signingKey() {
        byte[] secretBytes = authProperties.jwt().secret().getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(secretBytes);
    }
}
