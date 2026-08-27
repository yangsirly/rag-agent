package yangsirly.rag_agent.authentication;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 认证组件装配。
 *
 * <p>
 * TokenBlacklist 的选型同样不能用 @Component + @ConditionalOnBean
 * （组件扫描早于自动配置，条件恒为 false），改为 ObjectProvider
 * 在实例化期解析：Redis 自动配置生效 → RedisTokenBlacklist（多实例共享），
 * 否则内存实现（单机/测试）。
 * </p>
 */
@Configuration
public class AuthConfiguration {

    /**
     * 运行期按 Redis 可用性选择黑名单实现。
     *
     * <p>
     * Redis 可用时返回共享黑名单（多实例一致）；否则退化为内存黑名单（单机/测试）。
     * </p>
     */
    @Bean
    public TokenBlacklist tokenBlacklist(ObjectProvider<StringRedisTemplate> redisTemplateProvider) {
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate != null) {
            return new RedisTokenBlacklist(redisTemplate);
        }
        return new InMemoryTokenBlacklist();
    }
}
