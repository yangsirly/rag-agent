package yangsirly.rag_agent.authentication;

/**
 * JWT 签发与校验端口。
 *
 * <p>当前里程碑只搭骨架：接口契约已经固定，真正的 HMAC 签发/验签
 * 在后续任务中实现。Controller 和过滤器应只依赖本接口，便于测试替换。</p>
 */
public interface JwtTokenService {

	/**
	 * 为已认证用户签发 Access Token。
	 *
	 * @param user 已通过密码校验的用户主体
	 * @return JWT 字符串（紧凑序列化形式）
	 */
	String issueAccessToken(AuthenticatedUser user);

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
}
