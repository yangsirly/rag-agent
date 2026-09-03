package yangsirly.rag_agent.authentication;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Date;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import yangsirly.rag_agent.registration.User;

/**
 * HMAC Access JWT 实现。
 *
 * <p>JWT 只承担短期访问凭证职责；长期 Refresh 凭证由数据库会话表管理。
 * Access JWT 通过 typ 和 sid 与 Refresh 会话绑定，避免把另一种凭证误当成访问令牌。</p>
 */
@Service
public class JwtTokenServiceImpl implements JwtTokenService {

    static final String CLAIM_EMAIL = "email";
    static final String CLAIM_ROLE = "role";
    static final String CLAIM_STATUS = "status";
    static final String CLAIM_JTI = "jti";
    static final String CLAIM_SESSION_ID = "sid";
    static final String ACCESS_TOKEN_TYPE = "access";

    private final AuthProperties authProperties;
    private final Clock clock;

    /** Spring 使用可替换的 UTC Clock；测试可注入 FixedClock。 */
    @Autowired
    public JwtTokenServiceImpl(AuthProperties authProperties, Clock clock) {
        this.authProperties = authProperties;
        this.clock = clock;
    }

    /** 保留直接实例化的旧测试构造器，生产 Bean 走上面的注入构造器。 */
    public JwtTokenServiceImpl(AuthProperties authProperties) {
        this(authProperties, Clock.systemUTC());
    }

    @Override
    public IssuedAccessToken issueAccessToken(AuthenticatedUser user, String sessionId) {
        if (user == null || user.userId() == null || sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Authenticated user, userId and sessionId must not be null");
        }
        Date issuedAt = Date.from(clock.instant());
        Date expiresAt = new Date(issuedAt.getTime() + authProperties.jwt().accessTokenTtlSeconds() * 1000L);
        String jti = UUID.randomUUID().toString();
        String value = Jwts.builder()
                .header().type(ACCESS_TOKEN_TYPE).and()
                .subject(String.valueOf(user.userId()))
                .issuer(authProperties.jwt().issuer())
                .issuedAt(issuedAt)
                .expiration(expiresAt)
                .id(jti)
                .claim(CLAIM_JTI, jti)
                .claim(CLAIM_SESSION_ID, sessionId)
                .claim(CLAIM_EMAIL, user.email())
                .claim(CLAIM_ROLE, user.role().name())
                .claim(CLAIM_STATUS, user.status().name())
                .signWith(signingKey())
                .compact();
        return new IssuedAccessToken(value, jti, sessionId, issuedAt, expiresAt);
    }

    @Override
    public AuthenticatedUser parseAccessToken(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidAccessTokenException("Access token must not be blank");
        }
        try {
            Jws<Claims> signed = parseClaims(token);
            if (!ACCESS_TOKEN_TYPE.equals(signed.getHeader().getType())) {
                throw new InvalidAccessTokenException("Token type is not access");
            }
            Claims claims = signed.getPayload();
            Long userId = Long.valueOf(claims.getSubject());
            String email = claims.get(CLAIM_EMAIL, String.class);
            String roleName = claims.get(CLAIM_ROLE, String.class);
            String statusName = claims.get(CLAIM_STATUS, String.class);
            String jti = claims.getId();
            String sessionId = claims.get(CLAIM_SESSION_ID, String.class);
            if (jti == null) {
                jti = claims.get(CLAIM_JTI, String.class);
            }
            if (claims.getExpiration() == null || email == null || roleName == null || statusName == null
                    || jti == null || sessionId == null || sessionId.isBlank()) {
                throw new InvalidAccessTokenException("Access token claims are incomplete");
            }
            return new AuthenticatedUser(userId, email, User.Role.valueOf(roleName), User.Status.valueOf(statusName));
        } catch (InvalidAccessTokenException exception) {
            throw exception;
        } catch (JwtException | IllegalArgumentException exception) {
            throw new InvalidAccessTokenException("Access token is invalid or expired", exception);
        }
    }

    @Override
    public String extractJti(String token) {
        try {
            Jws<Claims> signed = parseClaims(token);
            if (!ACCESS_TOKEN_TYPE.equals(signed.getHeader().getType())) {
                return null;
            }
            Claims claims = signed.getPayload();
            String jti = claims.getId();
            return jti != null ? jti : claims.get(CLAIM_JTI, String.class);
        } catch (Exception exception) {
            return null;
        }
    }

    @Override
    public Date extractExpiration(String token) {
        try {
            Jws<Claims> signed = parseClaims(token);
            return ACCESS_TOKEN_TYPE.equals(signed.getHeader().getType())
                    ? signed.getPayload().getExpiration()
                    : null;
        } catch (Exception exception) {
            return null;
        }
    }

    @Override
    public String extractSessionId(String token) {
        try {
            Jws<Claims> signed = parseClaims(token);
            return ACCESS_TOKEN_TYPE.equals(signed.getHeader().getType())
                    ? signed.getPayload().get(CLAIM_SESSION_ID, String.class)
                    : null;
        } catch (Exception exception) {
            return null;
        }
    }

    private Jws<Claims> parseClaims(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidAccessTokenException("Access token must not be blank");
        }
        return Jwts.parser()
                .verifyWith(signingKey())
                .requireIssuer(authProperties.jwt().issuer())
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token);
    }

    private SecretKey signingKey() {
        byte[] secretBytes = authProperties.jwt().secret().getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(secretBytes);
    }
}
