package yangsirly.rag_agent.common.ratelimit;

import java.time.Duration;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Redis Lua 固定窗口限流，多实例共享计数。
 * 由 {@link RateLimitConfiguration} 在 Redis 可用时装配。
 * 学习笔记：docs/learning/milestone-06#3.2 Lua 原子性
 */
public class RedisRateLimiter implements RateLimiter {

    private final StringRedisTemplate redisTemplate;

    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>(
            "local c = redis.call('INCR', KEYS[1]); if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]); end; return c;",
            Long.class);

    /**
     * 构造 Redis 限流器。
     */
    public RedisRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 基于 Redis Lua 的固定窗口计数。
     *
     * <p>
     * INCR + EXPIRE 在同一脚本中原子执行，避免并发下漏设过期时间。
     * </p>
     */
    @Override
    public boolean tryAcquire(String key, int limit, Duration window) {
        try {
            // 执行 Lua 脚本进行原子递增计数 + 首次写入时设置过期时间
            // KEYS[1] 对应传入的限流桶 key（如 "rate:user:12345"）
            // ARGV[1] 对应过期时间（秒）
            Long count = redisTemplate.execute(SCRIPT,
                    List.of("rate:" + key), // KEYS[1]：限流桶唯一标识
                    String.valueOf(window.toSeconds()) // ARGV[1]：过期时间（秒）
            );
            return count != null && count <= limit;
        } catch (Exception e) {
            // fail-open：Redis 故障时放行而不是拒绝全部流量。
            // 代价是限流暂时失效；最终防线仍是登录失败锁定与 DB 唯一约束。
            return true;
        }
    }
}
