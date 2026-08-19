package yangsirly.rag_agent.authentication;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 从 Cookie 中读取 JWT，并把认证结果写入 {@link SecurityContextHolder}。
 *
 * <p>
 * 该过滤器在安全过滤器链中、鉴权之前执行。读取 Cookie 的流程已搭好，
 * </p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private final JwtTokenService jwtTokenService;
	private final AuthProperties authProperties;

	public JwtAuthenticationFilter(JwtTokenService jwtTokenService, AuthProperties authProperties) {
		this.jwtTokenService = jwtTokenService;
		this.authProperties = authProperties;
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request,
			HttpServletResponse response,
			FilterChain filterChain) throws ServletException, IOException {
		// 若当前请求尚未建立认证，再尝试从 Cookie 解析 JWT。
		if (SecurityContextHolder.getContext().getAuthentication() == null) {
			String token = extractAccessToken(request);
			if (token != null && !token.isBlank()) {
				authenticateFromToken(token);
			}
		}

		// 无论是否解析成功，都继续过滤器链：未认证请求由后续授权规则返回 401。
		filterChain.doFilter(request, response);
	}

	/**
	 * 尝试解析 token 并写入 SecurityContext。
	 *
	 * <p>
	 * 解析失败时清除上下文并继续，不在这里直接写 401 响应体；
	 * 统一由 AuthenticationEntryPoint 输出 JSON 错误。
	 * </p>
	 */
	private void authenticateFromToken(String token) {
		try {
			AuthenticatedUser user = jwtTokenService.parseAccessToken(token);
			if (user == null) {
				return;
			}

			// ROLE_ 前缀是 Spring Security hasRole() 约定。
			SimpleGrantedAuthority authority = new SimpleGrantedAuthority("ROLE_" + user.role().name());
			UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(user, null,
					java.util.List.of(authority));
			SecurityContextHolder.getContext().setAuthentication(authentication);
		} catch (RuntimeException exception) {
			// 伪造、过期或未实现的 token 一律视为未登录，不泄露解析细节。
			SecurityContextHolder.clearContext();
		}
	}

	/** 从请求 Cookie 中取出配置名称对应的 Access Token。 */
	private String extractAccessToken(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		String cookieName = authProperties.cookie().name();
		for (Cookie cookie : cookies) {
			if (cookieName.equals(cookie.getName())) {
				return cookie.getValue();
			}
		}
		return null;
	}
}
