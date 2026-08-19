package yangsirly.rag_agent.authentication;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录、退出与当前用户的 HTTP 入口。
 *
 * <p>凭证放在 HttpOnly Cookie 中：浏览器自动携带，前端 JS 无法读取，
 * 可降低 XSS 直接窃取 token 的风险。后端在未登录时返回 401，
 * 跳转登录页属于前端职责。</p>
 */
@RestController
public class AuthController {

	private final AuthService authService;
	private final AuthProperties authProperties;

	public AuthController(AuthService authService, AuthProperties authProperties) {
		this.authService = authService;
		this.authProperties = authProperties;
	}

	/**
	 * 处理 POST /login。
	 *
	 * <p>成功时写入 HttpOnly Cookie，响应体只返回 role 等非机密信息。</p>
	 */
	@PostMapping("/login")
	public ResponseEntity<LoginResponse> login(
			@Valid @RequestBody LoginRequest request,
			HttpServletResponse response) {
		// Web 层请求对象转换成业务命令，避免业务依赖 HTTP 输入模型。
		LoginCommand command = new LoginCommand(request.email(), request.password());

		AuthService.LoginResult result = authService.login(command);

		// 把 Access Token 写入 HttpOnly Cookie，而不是 JSON 响应体。
		writeAccessTokenCookie(response, result.accessToken());

		return ResponseEntity.ok(new LoginResponse(
				HttpStatus.OK.value(),
				result.user().role().name()));
	}

	/**
	 * 处理 POST /logout。
	 *
	 * <p>通过覆盖同名 Cookie 并设置 Max-Age=0 清除浏览器中的登录凭证。
	 * 第一阶段 JWT 无服务端黑名单，退出后旧 token 在过期前理论上仍可被持有者使用；
	 * 这是“短有效期 + 无 Refresh Token”方案的已知边界，后续可再引入撤销机制。</p>
	 */
	@PostMapping("/logout")
	public ResponseEntity<LogoutResponse> logout(HttpServletResponse response) {
		clearAccessTokenCookie(response);
		return ResponseEntity.ok(new LogoutResponse(HttpStatus.OK.value()));
	}

	/**
	 * 处理 GET /me。
	 *
	 * <p>前端启动或刷新时调用，用 Cookie 中的 JWT 恢复 userId / email / role。
	 * 未登录由安全过滤器链返回 401 {@code UNAUTHORIZED}，本方法仅在已认证时进入。</p>
	 *
	 * <p>主体来自 {@link JwtAuthenticationFilter} 写入的 SecurityContext；
	 * 查库与拒绝 DISABLED 在 {@link AuthService#me}。</p>
	 */
	@GetMapping("/me")
	public ResponseEntity<MeResponse> me(Authentication authentication) {
		// principal 类型由 JwtAuthenticationFilter 保证为 AuthenticatedUser
		AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();

		// 业务异常由 AuthenticationExceptionHandler 映射为 401 JSON
		AuthService.MeResult result = authService.me(principal);

		// userId 按契约序列化为十进制字符串
		return ResponseEntity.ok(new MeResponse(
				HttpStatus.OK.value(),
				String.valueOf(result.userId()),
				result.email(),
				result.role().name()));
	}

	/** 写入登录 Cookie：HttpOnly + 配置中的 Secure/SameSite/Path。 */
	private void writeAccessTokenCookie(HttpServletResponse response, String accessToken) {
		AuthProperties.Cookie cookieConfig = authProperties.cookie();
		ResponseCookie cookie = ResponseCookie.from(cookieConfig.name(), accessToken)
				.httpOnly(true)
				.secure(cookieConfig.secure())
				.sameSite(cookieConfig.sameSite())
				.path(cookieConfig.path())
				.maxAge(authProperties.jwt().accessTokenTtlSeconds())
				.build();
		// ResponseCookie 能正确序列化 SameSite；Servlet Cookie API 对 SameSite 支持较弱。
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
	}

	/** 用 Max-Age=0 的同名 Cookie 通知浏览器删除登录凭证。 */
	private void clearAccessTokenCookie(HttpServletResponse response) {
		AuthProperties.Cookie cookieConfig = authProperties.cookie();
		ResponseCookie cookie = ResponseCookie.from(cookieConfig.name(), "")
				.httpOnly(true)
				.secure(cookieConfig.secure())
				.sameSite(cookieConfig.sameSite())
				.path(cookieConfig.path())
				.maxAge(0)
				.build();
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
	}
}
