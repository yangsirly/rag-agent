package yangsirly.rag_agent.common.ratelimit;

import java.time.Duration;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 内存滑动窗口限流，用于单机/测试降级；多实例部署时由
 * {@link RateLimitConfiguration} 换成 Redis 实现。
 *
 * <p>
 * 实现要点：以 key 为单位的 Deque 记录窗口内每次请求的时间戳，
 * synchronized(deque) 保证"清理过期 + 判定 + 追加"的原子性。
 * 定期清扫全空/全过期的 key，避免攻击者轮换 IP 导致 map 无限增长。
 * </p>
 */
public class InMemoryRateLimiter implements RateLimiter {

    /** 触发全量清扫的 map 大小上限：超过说明 key 维度异常膨胀（如 IP 轮换攻击）。 */
    private static final int SWEEP_THRESHOLD = 10_000;

    private final ConcurrentHashMap<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    /**
     * 尝试在滑动窗口内获取许可。
     *
     * <p>
     * 同一 key 在同一时刻只允许一个线程进入关键区，保证
     * "清理过期 + 判断阈值 + 追加时间戳"的原子性。
     * </p>
     */
    @Override
    public boolean tryAcquire(String key, int limit, Duration window) {
        long now = System.currentTimeMillis();
        long windowMs = window.toMillis();
        if (windows.size() > SWEEP_THRESHOLD) {
            sweep(windowMs);
        }
        Deque<Long> deque = windows.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        synchronized (deque) {
            while (!deque.isEmpty() && now - deque.peekFirst() > windowMs) {
                deque.pollFirst();
            }
            if (deque.size() >= limit) {
                return false;
            }
            deque.addLast(now);
            return true;
        }
    }

    /**
     * 查询当前窗口剩余配额，用于响应头回传。
     */
    @Override
    public RateLimitInfo getInfo(String key, int limit, Duration window) {
        Deque<Long> deque = windows.get(key);
        int remaining = limit;
        if (deque != null) {
            synchronized (deque) {
                long now = System.currentTimeMillis();
                long windowMs = window.toMillis();
                while (!deque.isEmpty() && now - deque.peekFirst() > windowMs) {
                    deque.pollFirst();
                }
                remaining = Math.max(0, limit - deque.size());
            }
        }
        return new RateLimitInfo(limit, remaining, window.toSeconds());
    }

    /** 移除已过期且不再使用的 key，防止 key 空间无限膨胀。 */
    private void sweep(long windowMs) {
        long now = System.currentTimeMillis();
        Iterator<java.util.Map.Entry<String, Deque<Long>>> it = windows.entrySet().iterator();
        while (it.hasNext()) {
            Deque<Long> deque = it.next().getValue();
            synchronized (deque) {
                while (!deque.isEmpty() && now - deque.peekFirst() > windowMs) {
                    deque.pollFirst();
                }
                if (deque.isEmpty()) {
                    it.remove();
                }
            }
        }
    }
}
