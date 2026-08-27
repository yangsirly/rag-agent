package yangsirly.rag_agent.authentication;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 版 JWT jti 黑名单（多实例共享）。
 * 由 SecurityConfiguration 在 Redis 可用时装配。
 * TTL 与 token 剩余有效期对齐，避免 key 永久膨胀。
 * 学习笔记：docs/learning/milestone-06-industrial-hardening.md#3.4
 */
public class RedisTokenBlacklist implements TokenBlacklist {

    private final StringRedisTemplate redis;

    /**
     * 构造 Redis 黑名单实现。
     *
     * @param redis Redis 访问入口
     */
    public RedisTokenBlacklist(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 统一黑名单 key 前缀，避免与其它业务键冲突。
     */
    private static String key(String jti) {
        return "blacklist:jti:" + jti;
    }

    /**
     * 写入黑名单键并设置 TTL。
     *
     * <p>
     * TTL 必须与 token 剩余有效期一致，保证黑名单自动收敛。
     * </p>
     */
    @Override
    public void blacklist(String jti, Duration ttl) {
        if (jti == null || jti.isBlank())
            return;
        try {
            redis.opsForValue().set(key(jti), "1", ttl.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // Redis 写入失败时不阻断 logout：Cookie 已清除，黑名单是纵深防御的一层。
        }
    }

    /**
     * 查询 jti 是否命中黑名单。
     *
     * <p>
     * Redis 故障时按未命中降级，避免把 Redis 可用性变成登录可用性的单点。
     * </p>
     */
    @Override
    public boolean isBlacklisted(String jti) {
        if (jti == null || jti.isBlank())
            return false;
        try {
            return Boolean.TRUE.equals(redis.hasKey(key(jti)));
        } catch (Exception e) {
            // Redis 不可用时按未命中处理：降级为"黑名单暂时失效"，
            // 已 logout 的 token 至多在故障窗口内继续可用，直到自身过期。
            return false;
        }
    }
}
