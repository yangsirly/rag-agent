package yangsirly.rag_agent.authentication;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.core.instrument.MeterRegistry;

import yangsirly.rag_agent.registration.UserEntity;
import yangsirly.rag_agent.registration.UserMapper;

/**
 * Refresh 会话的创建、一次性轮换和撤销边界。
 *
 * <p>刷新事务对 session 行加排他锁：同一枚 Refresh Token 的并发请求只能有一个
 * 看到旧哈希并完成轮换，后续请求会被视为重放并撤销整个设备会话。</p>
 */
@Service
public class RefreshSessionService {

    private static final Logger log = LoggerFactory.getLogger(RefreshSessionService.class);

    private final RefreshSessionMapper refreshSessionMapper;
    private final UserMapper userMapper;
    private final JwtTokenService jwtTokenService;
    private final TokenBlacklist tokenBlacklist;
    private final AuthProperties authProperties;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    public RefreshSessionService(RefreshSessionMapper refreshSessionMapper, UserMapper userMapper,
            JwtTokenService jwtTokenService, TokenBlacklist tokenBlacklist, AuthProperties authProperties,
            Clock clock, ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.refreshSessionMapper = refreshSessionMapper;
        this.userMapper = userMapper;
        this.jwtTokenService = jwtTokenService;
        this.tokenBlacklist = tokenBlacklist;
        this.authProperties = authProperties;
        this.clock = clock;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    /** 登录成功后创建一个独立设备会话。 */
    public TokenPair createSession(AuthenticatedUser user) {
        RefreshTokenUtil.Generated generated = RefreshTokenUtil.generate();
        IssuedAccessToken access = jwtTokenService.issueAccessToken(user, generated.sessionId());
        LocalDateTime now = now();
        LocalDateTime refreshExpiresAt = now.plusSeconds(authProperties.jwt().refreshTokenTtlSeconds());
        RefreshSessionEntity session = new RefreshSessionEntity(
                generated.sessionId(),
                user.userId(),
                RefreshTokenUtil.hash(generated.token()),
                access.jti(),
                toUtcLocalDateTime(access.expiresAt()),
                refreshExpiresAt,
                now,
                now);
        int inserted = refreshSessionMapper.insert(session);
        if (inserted != 1) {
            throw new IllegalStateException("Refresh session must insert exactly one row");
        }
        return new TokenPair(access, generated.token(), refreshExpiresAt);
    }

    /**
     * 严格一次性轮换 Refresh Token。noRollbackFor 保证已经记录的重放撤销不会
     * 因为随后返回 401 的运行时异常而回滚。
     */
    @Transactional(noRollbackFor = InvalidRefreshTokenException.class)
    public TokenPair refresh(String rawRefreshToken) {
        String sessionId = RefreshTokenUtil.sessionId(rawRefreshToken);
        if (sessionId == null) {
            recordRefresh("invalid");
            throw new InvalidRefreshTokenException(InvalidRefreshTokenException.Reason.INVALID);
        }

        RefreshSessionEntity session = refreshSessionMapper.findByIdForUpdate(sessionId);
        if (session == null) {
            recordRefresh("invalid");
            throw new InvalidRefreshTokenException(InvalidRefreshTokenException.Reason.INVALID);
        }

        LocalDateTime now = now();
        if (session.getRevokedAt() != null) {
            recordRefresh("invalid");
            throw new InvalidRefreshTokenException(InvalidRefreshTokenException.Reason.INVALID);
        }
        if (!session.getExpiresAt().isAfter(now)) {
            refreshSessionMapper.revokeById(sessionId, now);
            blacklistCurrentAccess(session, now);
            recordRefresh("expired");
            throw new InvalidRefreshTokenException(InvalidRefreshTokenException.Reason.EXPIRED);
        }
        if (!RefreshTokenUtil.matches(rawRefreshToken, session.getTokenHash())) {
            // 旧 Token 被再次提交：这既可能是窃取后的重放，也可能是严格策略下的并发刷新。
            refreshSessionMapper.revokeById(sessionId, now);
            blacklistCurrentAccess(session, now);
            log.warn("Refresh token reuse detected; session revoked: sessionId={}", sessionId);
            recordRefresh("reused");
            throw new InvalidRefreshTokenException(InvalidRefreshTokenException.Reason.REUSED);
        }

        UserEntity user = userMapper.findById(session.getUserId());
        if (user == null || user.getStatus() != yangsirly.rag_agent.registration.User.Status.ACTIVE) {
            refreshSessionMapper.revokeById(sessionId, now);
            blacklistCurrentAccess(session, now);
            recordRefresh("disabled");
            throw new InvalidRefreshTokenException(InvalidRefreshTokenException.Reason.DISABLED);
        }

        AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getEmail(), user.getRole(),
                user.getStatus());
        RefreshTokenUtil.Generated next = RefreshTokenUtil.generateForSession(sessionId);
        IssuedAccessToken access = jwtTokenService.issueAccessToken(principal, sessionId);
        blacklistCurrentAccess(session, now);

        session.setTokenHash(RefreshTokenUtil.hash(next.token()));
        session.setCurrentAccessJti(access.jti());
        session.setCurrentAccessExpiresAt(toUtcLocalDateTime(access.expiresAt()));
        session.setLastUsedAt(now);
        if (refreshSessionMapper.updateById(session) != 1) {
            throw new IllegalStateException("Refresh session rotation must update exactly one row");
        }
        recordRefresh("success");
        return new TokenPair(access, next.token(), session.getExpiresAt());
    }

    /** 按当前 Token 撤销设备会话；不存在或已撤销时保持幂等。 */
    @Transactional
    public void revoke(String rawRefreshToken, String accessToken) {
        LocalDateTime now = now();
        if (accessToken != null) {
            blacklistAccess(accessToken, now);
        }
        String sessionId = RefreshTokenUtil.sessionId(rawRefreshToken);
        String accessSessionId = accessToken == null ? null : jwtTokenService.extractSessionId(accessToken);
        if (sessionId == null && accessToken != null) {
            sessionId = accessSessionId;
        }
        if (sessionId != null) {
            RefreshSessionEntity session = refreshSessionMapper.findByIdForUpdate(sessionId);
            // 仅凭公开的 sessionId 不能替换/撤销别人的会话；有 Access 时则允许
            // 用已验签的 sid 完成幂等登出。Refresh Cookie 若存在仍需常量时间匹配。
            boolean refreshMatches = session != null
                    && sessionId.equals(RefreshTokenUtil.sessionId(rawRefreshToken))
                    && RefreshTokenUtil.matches(rawRefreshToken, session.getTokenHash());
            boolean accessMatches = session != null && sessionId.equals(accessSessionId);
            if (session != null && session.getRevokedAt() == null && (refreshMatches || accessMatches)) {
                refreshSessionMapper.revokeById(sessionId, now);
                blacklistCurrentAccess(session, now);
            }
        }
    }

    /** 删除已自然过期的会话；撤销记录会保留到 expires_at 之后用于重放识别。 */
    @Transactional
    public int deleteExpiredSessions() {
        return refreshSessionMapper.deleteExpired(now());
    }

    private void blacklistCurrentAccess(RefreshSessionEntity session, LocalDateTime now) {
        if (session.getCurrentAccessJti() == null || session.getCurrentAccessExpiresAt() == null) {
            return;
        }
        long ttlSeconds = Duration.between(now, session.getCurrentAccessExpiresAt()).getSeconds();
        if (ttlSeconds > 0) {
            tokenBlacklist.blacklist(session.getCurrentAccessJti(), Duration.ofSeconds(ttlSeconds));
        }
    }

    private void blacklistAccess(String accessToken, LocalDateTime now) {
        String jti = jwtTokenService.extractJti(accessToken);
        java.util.Date expiresAt = jwtTokenService.extractExpiration(accessToken);
        if (jti == null || expiresAt == null) {
            return;
        }
        long ttlSeconds = Duration.between(now.toInstant(ZoneOffset.UTC), expiresAt.toInstant()).getSeconds();
        if (ttlSeconds > 0) {
            tokenBlacklist.blacklist(jti, Duration.ofSeconds(ttlSeconds));
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static LocalDateTime toUtcLocalDateTime(java.util.Date date) {
        return LocalDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC);
    }

    private void recordRefresh(String outcome) {
        if (meterRegistry != null) {
            meterRegistry.counter("auth.refresh", "outcome", outcome).increment();
        }
    }
}
