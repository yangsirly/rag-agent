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

import yangsirly.rag_agent.common.ratelimit.AuthLockProperties;
import yangsirly.rag_agent.common.ratelimit.RateLimitProperties;

/**
 * 安全过滤器链配置。
 *
 * <p>
 * CSRF 说明：认证凭证放在 HttpOnly Cookie，理论上需要 CSRF 防护。
 * 当前作为等价防护依赖 SameSite=Lax（Cookie 配置见 application.properties
 * 的 security.auth.cookie.same-site）：跨站 POST 不携带 Cookie，
 * 因此表单/JSON POST 无法跨站伪造登录态。若未来改为 SameSite=None
 * 或引入跨站前端，必须启用 CSRF 或改用 Authorization 头。
 * </p>
 */
@Configuration
@EnableConfigurationProperties({ AuthProperties.class, RateLimitProperties.class, AuthLockProperties.class })
public class SecurityConfiguration {

        /**
         * 占位 UserDetailsService。
         *
         * <p>
         * 当前系统走 AuthService 密码登录 + JWT Cookie，不走
         * Spring Security 默认 DaoAuthenticationProvider。
         * </p>
         */
        @Bean
        public UserDetailsService userDetailsService() {
                return username -> {
                        throw new UsernameNotFoundException(
                                        "Password login uses AuthService; JWT cookie does not look up users by this service");
                };
        }

        /**
         * 组装无状态安全过滤器链。
         */
        @Bean
        public SecurityFilterChain securityFilterChain(
                        HttpSecurity http,
                        JwtAuthenticationFilter jwtAuthenticationFilter,
                        JsonUnauthorizedAuthenticationEntryPoint authenticationEntryPoint,
                        JsonAccessDeniedHandler accessDeniedHandler) throws Exception {
                // TraceIdFilter / RateLimitFilter 是 @Component 注册的 Servlet 过滤器
                // （@Order 紧随其后），在安全链之前执行，不需要挂进安全链。
                http
                                .sessionManagement(session -> session
                                                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                                .csrf(AbstractHttpConfigurer::disable)
                                .httpBasic(AbstractHttpConfigurer::disable)
                                .formLogin(AbstractHttpConfigurer::disable)
                                .logout(AbstractHttpConfigurer::disable)
                                .exceptionHandling(exceptions -> exceptions
                                                .authenticationEntryPoint(authenticationEntryPoint)
                                                .accessDeniedHandler(accessDeniedHandler))
                                .authorizeHttpRequests(authorize -> authorize
                                                .requestMatchers(HttpMethod.POST, "/register", "/login").permitAll()
                                                .requestMatchers(HttpMethod.POST, "/logout").permitAll()
                                                .requestMatchers("/actuator/health", "/actuator/prometheus",
                                                                "/actuator/metrics")
                                                .permitAll()
                                                .anyRequest().authenticated())
                                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

                http.cors(Customizer.withDefaults());

                return http.build();
        }
}
