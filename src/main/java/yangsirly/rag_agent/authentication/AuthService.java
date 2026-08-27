package yangsirly.rag_agent.authentication;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import yangsirly.rag_agent.common.exception.RateLimitExceededException;
import yangsirly.rag_agent.common.ratelimit.AuthLockProperties;
import yangsirly.rag_agent.registration.User;
import yangsirly.rag_agent.registration.UserEntity;
import yangsirly.rag_agent.registration.UserMapper;

/**
 * 登录与当前用户查询。
 *
 * <p>
 * 防刷设计（需求 2.3：连续失败 5 次锁定 15 分钟，Redis 计数 + users.lock_until 兜底）：
 * 失败计数优先写 Redis（带锁定时长 TTL 的 INCR）——密码爆破风暴下每个失败请求
 * 不必都打一行 UPDATE 到数据库；计数达到阈值才落库写 lock_until（DB 是最终仲裁，
 * Redis 丢失只影响计数、不影响锁定状态）。Redis 不可用时退化为纯 DB 计数。
 * </p>
 */
@Service
public class AuthService {

    private final JwtTokenService jwtTokenService;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;
    private final AuthLockProperties authLockProperties;
    private final StringRedisTemplate stringRedisTemplate;

    public AuthService(
            JwtTokenService jwtTokenService,
            UserMapper userMapper,
            PasswordEncoder passwordEncoder,
            Clock clock,
            AuthLockProperties authLockProperties,
            @Autowired(required = false) StringRedisTemplate stringRedisTemplate) {
        this.jwtTokenService = jwtTokenService;
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.clock = clock;
        this.authLockProperties = authLockProperties;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 登录：校验凭证，必要时记录失败计数。
     *
     * <p>
     * 不加 @Transactional：失败计数、锁定标记的写入必须在抛出
     * {@link InvalidCredentialsException} 之前就提交——否则异常会回滚
     * 整个事务，lock_until / failed_login_count 永远落不了库，防刷形同虚设。
     * 读 user 与写计数都是单条语句，不需要事务保证原子性。
     * </p>
     */
    public LoginResult login(LoginCommand command) {
        String email = command.email().toLowerCase().strip();
        String password = command.password();
        if (email.isEmpty() || password.isEmpty()) {
            throw new InvalidCredentialsException();
        }

        UserEntity user = userMapper.findByEmail(email);

        // 锁定检查在密码校验之前：锁定期内即使密码正确也拒绝。
        // users.lock_until 是最终仲裁（Redis 计数丢失不影响锁定）。
        if (user != null && user.getLockUntil() != null) {
            LocalDateTime now = LocalDateTime.now(clock);
            if (user.getLockUntil().isAfter(now)) {
                throw new RateLimitExceededException("Account locked, try later", 60);
            }
        }

        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            if (user != null) {
                handleFailedAttempt(user, email);
            }
            throw new InvalidCredentialsException();
        }

        if (user.getStatus() == User.Status.DISABLED) {
            throw new UserDisabledException();
        }

        // 登录成功：清空失败计数（Redis 与 DB 都复位）。
        clearFailureCounter(email);
        if (user.getFailedLoginCount() != null && user.getFailedLoginCount() > 0) {
            user.setFailedLoginCount(0);
            user.setLockUntil(null);
            userMapper.updateById(user);
        }

        AuthenticatedUser authenticatedUser = new AuthenticatedUser(
                user.getId(),
                user.getEmail(),
                user.getRole(),
                user.getStatus());
        String accessToken = jwtTokenService.issueAccessToken(authenticatedUser);
        return new LoginResult(authenticatedUser, accessToken);
    }

    /**
     * 记录一次失败尝试，必要时触发锁定。
     *
     * <p>
     * Redis 可用：INCR login:fail:{email}（首次设置 TTL=锁定时长），
     * 达到阈值时写 users.lock_until 并删除计数；每轮锁定重新计数。
     * Redis 不可用：退化为 DB 的 failed_login_count 累加。
     * </p>
     */
    private void handleFailedAttempt(UserEntity user, String email) {
        int threshold = authLockProperties != null ? authLockProperties.lockThreshold() : 5;
        int lockMinutes = authLockProperties != null ? authLockProperties.lockDurationMinutes() : 15;

        if (stringRedisTemplate != null) {
            try {
                String key = "login:fail:" + email;
                Long count = stringRedisTemplate.opsForValue().increment(key);
                if (count != null) {
                    if (count == 1L) {
                        // 窗口与锁定时长对齐：锁定过期后计数自然归零。
                        stringRedisTemplate.expire(key, Duration.ofMinutes(lockMinutes));
                    }
                    if (count >= threshold) {
                        // 达到阈值：落库锁定并清零计数，开始新一轮窗口。
                        user.setLockUntil(LocalDateTime.now(clock).plusMinutes(lockMinutes));
                        user.setFailedLoginCount(0);
                        userMapper.updateById(user);
                        stringRedisTemplate.delete(key);
                    }
                    return;
                }
            } catch (Exception ignored) {
                // Redis 故障：走下面的 DB 兜底路径。
            }
        }

        // DB 兜底计数：无 Redis（单机/测试）或 Redis 故障时使用。
        int count = user.getFailedLoginCount() == null ? 0 : user.getFailedLoginCount();
        count++;
        user.setFailedLoginCount(count);
        if (count >= threshold) {
            user.setLockUntil(LocalDateTime.now(clock).plusMinutes(lockMinutes));
            user.setFailedLoginCount(0);
        }
        userMapper.updateById(user);
    }

    private void clearFailureCounter(String email) {
        if (stringRedisTemplate != null) {
            try {
                stringRedisTemplate.delete("login:fail:" + email);
            } catch (Exception ignored) {
                // 计数 key 有 TTL，删除失败最多多计几次失败，不影响正确性。
            }
        }
    }

    /**
     * 获取当前登录用户的精简信息。
     *
     * <p>
     * 返回前再次读取数据库状态，确保被禁用账号不能继续访问受保护资源。
     * </p>
     */
    @Transactional(readOnly = true)
    public MeResult me(AuthenticatedUser principal) {
        if (principal == null || principal.userId() == null) {
            throw new CurrentUserUnavailableException();
        }
        UserEntity user = userMapper.findById(principal.userId());
        if (user == null || user.getStatus() == User.Status.DISABLED) {
            throw new CurrentUserUnavailableException();
        }
        return new MeResult(
                user.getId(),
                user.getEmail(),
                user.getRole());
    }

    /** 登录结果：携带当前用户与签发的访问令牌。 */
    public record LoginResult(AuthenticatedUser user, String accessToken) {
    }

    /** /me 接口返回模型：仅暴露最小身份信息。 */
    public record MeResult(Long userId, String email, User.Role role) {
    }
}
