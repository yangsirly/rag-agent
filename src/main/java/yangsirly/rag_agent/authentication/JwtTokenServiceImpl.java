package yangsirly.rag_agent.authentication;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import yangsirly.rag_agent.registration.User;

/**
 * 基于 HMAC-SHA 的 JWT 签发与校验实现。
 *
 * <p>
 * Token 为无状态凭证：解析时不查库，只校验签名、过期时间与 issuer，
 * 再从 claims 还原 {@link AuthenticatedUser}。
 * </p>
 */
@Service
public class JwtTokenServiceImpl implements JwtTokenService {

	/** claim 名：邮箱（便于日志关联与主体还原，不作为授权依据） */
	static final String CLAIM_EMAIL = "email";

	/** claim 名：角色枚举名，过滤器据此构造 ROLE_* 权限 */
	static final String CLAIM_ROLE = "role";

	/** claim 名：账号状态；登录后若禁用，后续可在业务层再拒绝 */
	static final String CLAIM_STATUS = "status";

	private final AuthProperties authProperties;

	public JwtTokenServiceImpl(AuthProperties authProperties) {
		this.authProperties = authProperties;
	}

	/**
	 * 签发 Access Token。
	 *
	 * <p>
	 * 固定 claims：
	 * <ul>
	 * <li>{@code sub} = userId</li>
	 * <li>{@code iss} = 配置中的 issuer</li>
	 * <li>{@code exp} / {@code iat} = 过期与签发时间</li>
	 * <li>{@code email} / {@code role} / {@code status} = 还原主体所需字段</li>
	 * </ul>
	 * 使用配置密钥做 HMAC 签名；密钥长度需满足 HS256 最低要求（≥ 256 bit）。
	 * </p>
	 */
	@Override
	public String issueAccessToken(AuthenticatedUser user) {
		if (user == null || user.userId() == null) {
			throw new IllegalArgumentException("Authenticated user and userId must not be null");
		}

		Date issuedAt = new Date();
		// TTL 来自配置（秒），转成绝对过期时间写入 exp
		Date expiresAt = new Date(issuedAt.getTime()
				+ authProperties.jwt().accessTokenTtlSeconds() * 1000L);

		return Jwts.builder()
				.subject(String.valueOf(user.userId()))
				.issuer(authProperties.jwt().issuer())
				.issuedAt(issuedAt)
				.expiration(expiresAt)
				.claim(CLAIM_EMAIL, user.email())
				.claim(CLAIM_ROLE, user.role().name())
				.claim(CLAIM_STATUS, user.status().name())
				.signWith(signingKey())
				.compact();
	}

	/**
	 * 校验并解析 Access Token。
	 *
	 * <p>
	 * 失败（签名错误、过期、issuer 不匹配、claim 缺失/非法）统一抛
	 * {@link InvalidAccessTokenException}，由过滤器当作未登录处理。
	 * </p>
	 */
	@Override
	public AuthenticatedUser parseAccessToken(String token) {
		if (token == null || token.isBlank()) {
			throw new InvalidAccessTokenException("Access token must not be blank");
		}

		try {
			// verifyWith + requireIssuer：签名与签发方都必须匹配
			Claims claims = Jwts.parser()
					.verifyWith(signingKey())
					.requireIssuer(authProperties.jwt().issuer())
					.build()
					.parseSignedClaims(token)
					.getPayload();

			Long userId = Long.valueOf(claims.getSubject());
			String email = claims.get(CLAIM_EMAIL, String.class);
			String roleName = claims.get(CLAIM_ROLE, String.class);
			String statusName = claims.get(CLAIM_STATUS, String.class);

			if (email == null || roleName == null || statusName == null) {
				throw new InvalidAccessTokenException("Access token claims are incomplete");
			}

			return new AuthenticatedUser(
					userId,
					email,
					User.Role.valueOf(roleName),
					User.Status.valueOf(statusName));
		} catch (JwtException | IllegalArgumentException exception) {
			// 伪造、过期、issuer 错误、枚举非法等：不向外暴露细节
			throw new InvalidAccessTokenException("Access token is invalid or expired", exception);
		}
	}

	/**
	 * 用配置中的 secret 构造 HMAC 密钥。
	 *
	 * <p>
	 * {@link Keys#hmacShaKeyFor} 要求密钥字节长度足够（HS256 至少 32 字节）。
	 * </p>
	 */
	private SecretKey signingKey() {
		byte[] secretBytes = authProperties.jwt().secret().getBytes(StandardCharsets.UTF_8);
		return Keys.hmacShaKeyFor(secretBytes);
	}
}
