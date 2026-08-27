package yangsirly.rag_agent.chat;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 聊天模块的基础设施装配。
 *
 * <p>
 * Clock 作为可注入的时间来源，测试可替换为 FixedClock。
 * StringRedisTemplate 不在这里定义——Boot 的 RedisAutoConfiguration
 * 已提供（引入 starter-data-redis 且自动配置生效时），这里再写
 * 
 * @ConditionalOnBean(RedisConnectionFactory) 反而因求值时机
 *                                            （早于自动配置）恒为 false，成为死代码。
 *                                            </p>
 */
@Configuration
public class ChatConfiguration {

    /**
     * 提供默认 UTC 时钟。
     *
     * <p>
     * 测试场景可覆盖此 Bean 注入固定时间。
     * </p>
     */
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock clock() {
        return Clock.systemUTC();
    }
}
