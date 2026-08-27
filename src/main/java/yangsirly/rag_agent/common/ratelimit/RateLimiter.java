package yangsirly.rag_agent.common.ratelimit;

import java.time.Duration;

/**
 * 限流端口，生产用 Redis Lua 令牌桶，测试/降级用内存实现。
 * 学习笔记：docs/learning/milestone-06#3.2
 */
public interface RateLimiter {
    /**
     * 尝试获取一次许可。
     * @param key 限流维度，如 ip:1.2.3.4:register
     * @param limit 窗口内最大次数
     * @param window 窗口时长
     * @return true=允许，false=限流
     */
    boolean tryAcquire(String key, int limit, Duration window);

    /**
     * 获取剩余次数与重置时间（用于响应头）。
     */
    default RateLimitInfo getInfo(String key, int limit, Duration window) {
        return new RateLimitInfo(limit, limit, window.toSeconds());
    }

    record RateLimitInfo(int limit, int remaining, long resetSeconds) {}
}
