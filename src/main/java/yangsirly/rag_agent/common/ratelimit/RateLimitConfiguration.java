package yangsirly.rag_agent.common.ratelimit;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 限流器装配。
 *
 * <p>
 * 不能用 {@code @Component + @ConditionalOnBean(StringRedisTemplate.class)}
 * 写在 {@code RedisRateLimiter}/{@code InMemoryRateLimiter} 上：组件扫描早于
 * Spring Boot 自动配置，条件求值时 {@code StringRedisTemplate} 的 Bean 定义
 * 还没注册，条件恒为 false，Redis 限流器在生产里根本不会被装配，全部
 * 静默退化为内存实现（多实例下各自计数，限流被放大 N 倍）。
 *
 * <p>
 * 正确做法：在 {@code @Configuration} 里用 {@link ObjectProvider#getIfAvailable()}
 * 在实例化期解析——那时所有 Bean 定义都已就位，Redis 自动配置生效时拿到
 * {@code StringRedisTemplate}，否则退回内存实现。学习笔记：
 * docs/learning/milestone-06-industrial-hardening.md#2.2
 * </p>
 */
@Configuration
public class RateLimitConfiguration {

    /**
     * 运行期按 Redis 可用性选择限流器实现。
     *
     * <p>
     * Redis 可用时使用多实例共享计数；否则退化为单机内存窗口。
     * </p>
     */
    @Bean
    public RateLimiter rateLimiter(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate != null) {
            return new RedisRateLimiter(redisTemplate);
        }
        return new InMemoryRateLimiter();
    }
}
