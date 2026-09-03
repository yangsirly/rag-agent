package yangsirly.rag_agent.authentication;

/**
 * Access JWT 签发与校验端口。
 *
 * <p>Refresh Token 不属于 JWT，也不应被这个端口解析；它由
 * {@link RefreshSessionMapper} 对应的会话服务负责一次性轮换。</p>
 */
public interface JwtTokenService {

	/**
	 * 为已认证用户签发带会话绑定的 Access Token。
	 *
	 * @param user 已通过密码校验的用户主体
	 * @param sessionId 当前设备的 Refresh 会话 ID
	 * @return 包含 JWT、jti 和过期时间的签发结果
	 */
	IssuedAccessToken issueAccessToken(AuthenticatedUser user, String sessionId);

	/**
	 * 兼容只关心字符串的旧调用方/单元测试；生产登录必须传入会话 ID。
	 */
	default String issueAccessToken(AuthenticatedUser user) {
		return issueAccessToken(user, java.util.UUID.randomUUID().toString()).value();
	}

	/**
	 * 解析并校验 Access Token。
	 *
	 * <p>校验应覆盖签名、过期时间、issuer 等；失败时抛出
	 * {@link InvalidAccessTokenException}，由过滤器统一视为未认证。</p>
	 *
	 * @param token 客户端携带的 JWT 字符串
	 * @return 解析后的已认证用户
	 */
	AuthenticatedUser parseAccessToken(String token);

	/**
	 * 提取 token 的 jti（JWT ID）。token 无效或缺失 jti 时返回 null，
	 * 不抛异常——调用方（黑名单检查）把 null 视为"无需查询"。
	 */
	String extractJti(String token);

	/**
	 * 提取 token 的过期时间。token 无效时返回 null。
	 * logout 计算黑名单 TTL 用：TTL 与剩余有效期对齐。
	 */
	java.util.Date extractExpiration(String token);

	/** 提取 Access JWT 绑定的 Refresh 会话 ID；无效时返回 null。 */
	String extractSessionId(String token);
}
