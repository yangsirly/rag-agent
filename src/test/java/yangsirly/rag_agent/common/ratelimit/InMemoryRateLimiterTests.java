package yangsirly.rag_agent.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * 内存滑动窗口限流器的行为单元测试（不依赖 Spring 上下文）。
 */
class InMemoryRateLimiterTests {

    @Test
    void allowsUpToLimitAndRejectsBeyond() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter();
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("k", 3, Duration.ofMinutes(1))).isTrue();
        }
        assertThat(limiter.tryAcquire("k", 3, Duration.ofMinutes(1))).isFalse();
    }

    @Test
    void differentKeysAreIndependent() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter();
        assertThat(limiter.tryAcquire("a", 1, Duration.ofMinutes(1))).isTrue();
        assertThat(limiter.tryAcquire("b", 1, Duration.ofMinutes(1))).isTrue();
        // a 已用尽，b 仍然只用了 1/1；a 再来被拒。
        assertThat(limiter.tryAcquire("a", 1, Duration.ofMinutes(1))).isFalse();
    }

    @Test
    void windowSlidesAfterExpiry() throws Exception {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter();
        assertThat(limiter.tryAcquire("k", 1, Duration.ofMillis(50))).isTrue();
        assertThat(limiter.tryAcquire("k", 1, Duration.ofMillis(50))).isFalse();
        // 窗口过期后额度恢复。
        Thread.sleep(80);
        assertThat(limiter.tryAcquire("k", 1, Duration.ofMillis(50))).isTrue();
    }

    @Test
    void getInfoReportsRemainingQuota() {
        InMemoryRateLimiter limiter = new InMemoryRateLimiter();
        limiter.tryAcquire("k", 5, Duration.ofMinutes(1));
        limiter.tryAcquire("k", 5, Duration.ofMinutes(1));
        RateLimiter.RateLimitInfo info = limiter.getInfo("k", 5, Duration.ofMinutes(1));
        assertThat(info.remaining()).isEqualTo(3);
        assertThat(info.limit()).isEqualTo(5);
    }
}
