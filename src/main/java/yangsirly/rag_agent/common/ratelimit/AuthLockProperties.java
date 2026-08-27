package yangsirly.rag_agent.common.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 登录失败锁定参数（app.auth.*）。
 *
 * @param lockThreshold       连续失败达到该值后触发锁定
 * @param lockDurationMinutes 锁定持续分钟数
 */
@ConfigurationProperties(prefix = "app.auth")
public record AuthLockProperties(int lockThreshold, int lockDurationMinutes) {
}
