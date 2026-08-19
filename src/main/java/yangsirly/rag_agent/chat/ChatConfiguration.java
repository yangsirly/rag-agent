package yangsirly.rag_agent.chat;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 聊天模块的可替换基础设施。
 *
 * <p>{@link Clock} 作为时间来源注入 Service，便于测试固定“现在”，
 * 也避免业务代码直接依赖系统时钟（后续若做多时区可在此替换）。</p>
 */
@Configuration
public class ChatConfiguration {

	/**
	 * 系统默认时区时钟。
	 *
	 * <p>若上下文中已有其他模块声明的 {@link Clock} Bean，可改为
	 * {@code @ConditionalOnMissingBean} 或共享同一 Bean；
	 * 当前项目尚无 Clock，由聊天模块提供。</p>
	 */
	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}
}
