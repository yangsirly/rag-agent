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
 * 从 Cookie 读取 JWT 并写入 SecurityContext，支持 jti 黑名单校验。
 *
 * <p>
 * 黑名单命中的 token 与无效 token 一样按"匿名"处理：
 * 不设置 SecurityContext，受保护接口由后续授权规则返回 401。
 * </p>
 * 学习笔记：docs/learning/milestone-06-industrial-hardening.md#3.4
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenService jwtTokenService;
    private final AuthProperties authProperties;
    private final TokenBlacklist tokenBlacklist;

    public JwtAuthenticationFilter(JwtTokenService jwtTokenService, AuthProperties authProperties,
            TokenBlacklist tokenBlacklist) {
        this.jwtTokenService = jwtTokenService;
        this.authProperties = authProperties;
        this.tokenBlacklist = tokenBlacklist;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = extractAccessToken(request);
            if (token != null && !token.isBlank()) {
                String jti = jwtTokenService.extractJti(token);
                if (jti == null || !tokenBlacklist.isBlacklisted(jti)) {
                    authenticateFromToken(token);
                }
                // jti 命中黑名单：跳过认证，保持匿名 → 后续 401。
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * 解析 token 并写入 SecurityContext。
     *
     * <p>
     * 任意解析异常都按匿名处理并清空上下文，避免脏认证对象残留。
     * </p>
     */
    private void authenticateFromToken(String token) {
        try {
            AuthenticatedUser user = jwtTokenService.parseAccessToken(token);
            if (user == null) {
                return;
            }
            SimpleGrantedAuthority authority = new SimpleGrantedAuthority("ROLE_" + user.role().name());
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(user, null,
                    java.util.List.of(authority));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (RuntimeException exception) {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 从访问令牌 Cookie 中提取 JWT 字符串。
     *
     * <p>
     * 只认配置的 Cookie 名称，避免误读其它业务 Cookie。
     * </p>
     */
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
