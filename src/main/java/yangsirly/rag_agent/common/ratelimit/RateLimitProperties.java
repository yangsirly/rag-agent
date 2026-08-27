package yangsirly.rag_agent.common.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 限流阈值配置，外化到 application.properties 的 app.rate-limit.*。
 *
 * @param registerPerIpPerMinute    注册接口：每 IP 每分钟
 * @param loginPerIpPerMinute       登录接口：每 IP 每分钟
 * @param loginPerAccountPerMinute  登录接口：每账号每分钟（目前仅作为
 *                                  AuthService 登录失败锁定的参考阈值来源之一，
 *                                  过滤器层不做账号级限流——JSON body 在 Filter
 *                                  里无法安全读取）
 * @param sendPerUserPerMinute      发送消息：每用户每分钟
 * @param trustForwardedFor         是否信任 X-Forwarded-For 的第一跳作为客户端 IP。
 *                                  只有部署在可信反向代理之后才应开启；
 *                                  默认 false，防止伪造头绕过 IP 限流
 */
@ConfigurationProperties(prefix = "app.rate-limit")
public record RateLimitProperties(
        int registerPerIpPerMinute,
        int loginPerIpPerMinute,
        int loginPerAccountPerMinute,
        int sendPerUserPerMinute,
        @org.springframework.boot.context.properties.bind.DefaultValue("false") boolean trustForwardedFor) {
}
