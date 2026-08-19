package yangsirly.rag_agent.authentication;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import yangsirly.rag_agent.registration.User;
import yangsirly.rag_agent.registration.UserEntity;
import yangsirly.rag_agent.registration.UserMapper;

/**
 * 认证业务逻辑边界（登录与当前用户）。
 *
 * <p>
 * Controller 只处理 HTTP；密码比对、用户状态检查、令牌签发与 {@code /me} 组装应集中在这里。
 * </p>
 */
@Service
public class AuthService {

	private final JwtTokenService jwtTokenService;

	private final UserMapper userMapper;

	// 与注册共用同一个 PasswordEncoder Bean，保证 encode / matches 算法一致
	private final PasswordEncoder passwordEncoder;

	public AuthService(
			JwtTokenService jwtTokenService,
			UserMapper userMapper,
			PasswordEncoder passwordEncoder) {
		this.jwtTokenService = jwtTokenService;
		this.userMapper = userMapper;
		this.passwordEncoder = passwordEncoder;
	}

	/**
	 * 校验账号密码并签发 Access Token。
	 *
	 * <p>
	 * 成功路径：
	 * <ol>
	 * <li>规范化邮箱并按邮箱查找用户；</li>
	 * <li>{@code PasswordEncoder.matches} 校验密码；</li>
	 * <li>拒绝 DISABLED 用户；</li>
	 * <li>组装 {@link AuthenticatedUser} 并签发 JWT。</li>
	 * </ol>
	 * 凭证失败统一抛 {@link InvalidCredentialsException}，不暴露账号是否存在。
	 * </p>
	 *
	 * @param command 登录命令
	 * @return 登录结果：已认证用户 + 待写入 Cookie 的 token
	 */
	@Transactional(readOnly = true)
	public LoginResult login(LoginCommand command) {
		String email = command.email().toLowerCase().strip();
		String password = command.password();
		if (email.isEmpty() || password.isEmpty()) {
			throw new InvalidCredentialsException();
		}

		// 一次查询拿到用户；用户不存在或密码错误都返回同一异常，避免账号枚举
		UserEntity user = userMapper.findByEmail(email);
		if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
			throw new InvalidCredentialsException();
		}

		// 密码正确后再检查状态：禁用账号用独立异常，对外 401 USER_DISABLED
		if (user.getStatus() == User.Status.DISABLED) {
			throw new UserDisabledException();
		}

		// 主体只携带授权所需字段；token 由 JwtTokenService 用 HMAC 签名
		AuthenticatedUser authenticatedUser = new AuthenticatedUser(
				user.getId(),
				user.getEmail(),
				user.getRole(),
				user.getStatus());
		String accessToken = jwtTokenService.issueAccessToken(authenticatedUser);
		return new LoginResult(authenticatedUser, accessToken);
	}

	/**
	 * 解析当前登录用户，供 {@code GET /me} 使用。
	 *
	 * <p>
	 * 调用方保证 {@code principal} 通常已由 JWT 过滤器写入 SecurityContext。
	 * 成功路径：
	 * </p>
	 * <ol>
	 * <li>用 {@code principal.userId()} 查库拿到最新用户；</li>
	 * <li>用户不存在或状态为 DISABLED 时抛 {@link CurrentUserUnavailableException}（映射 401）；</li>
	 * <li>返回库中最新的 userId / email / role，不长期只信 JWT claims。</li>
	 * </ol>
	 *
	 * @param principal 过滤器解析出的已认证主体
	 * @return 供 Controller 组装 {@link MeResponse} 的用户视图
	 */
	@Transactional(readOnly = true)
	public MeResult me(AuthenticatedUser principal) {
		if (principal == null || principal.userId() == null) {
			throw new CurrentUserUnavailableException();
		}
		UserEntity user = userMapper.findById(principal.userId());
		// 不存在与禁用对外同一异常，避免枚举“该 userId 是否还在库里”
		if (user == null || user.getStatus() == User.Status.DISABLED) {
			throw new CurrentUserUnavailableException();
		}
		return new MeResult(
				user.getId(),
				user.getEmail(),
				user.getRole());
	}

	/**
	 * 登录成功后的内部结果。
	 *
	 * <p>
	 * token 只在服务端写入 HttpOnly Cookie 时使用，不直接进入 JSON 响应体。
	 * </p>
	 *
	 * @param user        已认证用户
	 * @param accessToken 待写入 Cookie 的 Access Token
	 */
	public record LoginResult(AuthenticatedUser user, String accessToken) {
	}

	/**
	 * {@code GET /me} 的内部结果（不含 HTTP 外壳）。
	 *
	 * @param userId 用户主键
	 * @param email  邮箱
	 * @param role   角色
	 */
	public record MeResult(Long userId, String email, User.Role role) {
	}
}
