package yangsirly.rag_agent.authentication;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 登录认证相关配置的类型化绑定。
 *
 * <p>{@code @ConfigurationProperties(prefix = "security.auth")} 会把
 * {@code application.properties} 中以 {@code security.auth.*} 开头的配置
 * 映射到本记录字段。具体 Bean 注册见 {@link SecurityConfiguration}。</p>
 *
	 * @param jwt JWT 签发与校验参数
	 * @param cookie 登录凭证 Cookie 参数
 */
@ConfigurationProperties(prefix = "security.auth")
public record AuthProperties(Jwt jwt, Cookie cookie) {

	/**
	 * JWT 相关配置。
	 *
	 * @param secret HMAC 签名密钥；生产环境必须通过环境变量注入足够长的随机值
	 * @param issuer Token 签发方标识，校验时需要匹配
	 * @param accessTokenTtlSeconds Access Token 有效期（秒）
	 * @param refreshTokenTtlSeconds Refresh 会话的绝对有效期（秒）
	 */
	public record Jwt(String secret, String issuer, long accessTokenTtlSeconds, long refreshTokenTtlSeconds) {
	}

	/**
	 * 登录 Cookie 相关配置。
	 *
	 * @param name Access Token Cookie 名称
	 * @param refreshName Refresh Token Cookie 名称
	 * @param secure 是否仅通过 HTTPS 发送；本地 HTTP 开发通常为 false
	 * @param sameSite SameSite 策略，例如 Lax 或 Strict
	 * @param path Cookie 作用路径
	 */
	public record Cookie(String name, String refreshName, boolean secure, String sameSite, String path) {
	}
}
