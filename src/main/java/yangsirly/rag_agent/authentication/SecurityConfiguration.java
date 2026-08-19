package yangsirly.rag_agent.authentication;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 认证与授权的安全过滤器链配置。
 *
 * <p>类名使用 {@code SecurityConfiguration}，避免与 Spring Security 自带的
 * {@code AuthenticationConfiguration} 混淆。第一阶段采用无状态 JWT：服务器不创建
 * HttpSession，身份完全来自 Cookie 中的 token。</p>
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfiguration {

	/**
	 * 提供占位 {@link UserDetailsService}，阻止 Spring Boot 自动生成默认用户名/密码。
	 *
	 * <p>本项目登录走自定义 JWT Cookie，不使用全局内存用户表。若完全不声明该 Bean，
	 * Boot 会创建 {@code inMemoryUserDetailsManager} 并在日志打印随机密码，容易误导。</p>
	 */
	@Bean
	public UserDetailsService userDetailsService() {
		return username -> {
			throw new UsernameNotFoundException(
					"Password login uses AuthService; JWT cookie does not look up users by this service");
		};
	}

	/**
	 * 定义 HTTP 安全规则与过滤器顺序。
	 *
	 * <p>{@link SecurityFilterChain} 是 Spring Security 6+ 推荐的配置方式，
	 * 取代旧的 {@code WebSecurityConfigurerAdapter}。</p>
	 */
	@Bean
	public SecurityFilterChain securityFilterChain(
			HttpSecurity http,
			JwtAuthenticationFilter jwtAuthenticationFilter,
			JsonUnauthorizedAuthenticationEntryPoint authenticationEntryPoint,
			JsonAccessDeniedHandler accessDeniedHandler) throws Exception {
		http
				// 无状态 API：不创建也不使用 HttpSession。
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				// 第一阶段前后端分离 + JWT Cookie。CSRF 的完整策略见学习笔记；
				// 骨干阶段先关闭默认表单 CSRF，避免阻塞 /login JSON 请求。
				.csrf(AbstractHttpConfigurer::disable)
				// 不启用默认表单登录页和 HTTP Basic，避免浏览器弹出原生登录框。
				.httpBasic(AbstractHttpConfigurer::disable)
				.formLogin(AbstractHttpConfigurer::disable)
				.logout(AbstractHttpConfigurer::disable)
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint(authenticationEntryPoint)
						.accessDeniedHandler(accessDeniedHandler))
				.authorizeHttpRequests(authorize -> authorize
						// 注册与登录对匿名用户开放。
						.requestMatchers(HttpMethod.POST, "/register", "/login").permitAll()
						// 退出登录允许匿名调用：即使没有有效会话，也应能清除浏览器 Cookie。
						.requestMatchers(HttpMethod.POST, "/logout").permitAll()
						// 其余接口默认要求已认证；后续知识库接口再叠加角色规则。
						.anyRequest().authenticated())
				// 在 UsernamePasswordAuthenticationFilter 之前解析 Cookie JWT。
				.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

		// Customizer.withDefaults() 保留点：后续若启用 CORS 可在此扩展。
		http.cors(Customizer.withDefaults());

		return http.build();
	}
}
