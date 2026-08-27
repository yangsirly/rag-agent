package yangsirly.rag_agent.authentication;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import java.time.Duration;
import java.util.Date;

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
 * 认证接口入口：登录、登出、当前用户信息。
 */
@RestController
public class AuthController {

    private final AuthService authService;
    private final AuthProperties authProperties;
    private final TokenBlacklist tokenBlacklist;
    private final JwtTokenService jwtTokenService;

    public AuthController(AuthService authService, AuthProperties authProperties, TokenBlacklist tokenBlacklist,
            JwtTokenService jwtTokenService) {
        this.authService = authService;
        this.authProperties = authProperties;
        this.tokenBlacklist = tokenBlacklist;
        this.jwtTokenService = jwtTokenService;
    }

    /**
     * 密码登录：校验成功后签发 JWT 并写入 HttpOnly Cookie。
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse response) {
        LoginCommand command = new LoginCommand(request.email(), request.password());
        AuthService.LoginResult result = authService.login(command);
        writeAccessTokenCookie(response, result.accessToken());
        return ResponseEntity.ok(new LoginResponse(
                HttpStatus.OK.value(),
                result.user().role().name()));
    }

    /**
     * 登出：清除 Cookie，并尽量把当前 token 的 jti 写入黑名单。
     */
    @PostMapping("/logout")
    public ResponseEntity<LogoutResponse> logout(HttpServletRequest request, HttpServletResponse response) {
        // 若携带有效 token，则把它的 jti 写入黑名单至其自然过期，
        // 使"已复制走的 token"也随 logout 立即失效（多实例下由 Redis 共享）。
        try {
            String token = extractToken(request);
            if (token != null) {
                String jti = jwtTokenService.extractJti(token);
                Date exp = jwtTokenService.extractExpiration(token);
                if (jti != null && exp != null) {
                    long ttlSec = (exp.getTime() - System.currentTimeMillis()) / 1000;
                    if (ttlSec > 0) {
                        tokenBlacklist.blacklist(jti, Duration.ofSeconds(ttlSec));
                    }
                }
            }
        } catch (Exception ignored) {
            // 黑名单失败不阻断 logout：Cookie 清除仍然完成。
        }
        clearAccessTokenCookie(response);
        return ResponseEntity.ok(new LogoutResponse(HttpStatus.OK.value()));
    }

    /**
     * 返回当前登录用户的最小信息视图。
     */
    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(Authentication authentication) {
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        AuthService.MeResult result = authService.me(principal);
        return ResponseEntity.ok(new MeResponse(
                HttpStatus.OK.value(),
                String.valueOf(result.userId()),
                result.email(),
                result.role().name()));
    }

    /**
     * 从认证 Cookie 中提取 JWT。
     */
    private String extractToken(HttpServletRequest request) {
        if (request.getCookies() == null)
            return null;
        String name = authProperties.cookie().name();
        for (Cookie c : request.getCookies()) {
            if (name.equals(c.getName()))
                return c.getValue();
        }
        return null;
    }

    /**
     * 写入访问令牌 Cookie。
     *
     * <p>
     * Cookie 安全属性（secure/sameSite/path）统一由配置控制，
     * 避免代码散落魔法值。
     * </p>
     */
    private void writeAccessTokenCookie(HttpServletResponse response, String accessToken) {
        AuthProperties.Cookie cookieConfig = authProperties.cookie();
        ResponseCookie cookie = ResponseCookie.from(cookieConfig.name(), accessToken)
                .httpOnly(true)
                .secure(cookieConfig.secure())
                .sameSite(cookieConfig.sameSite())
                .path(cookieConfig.path())
                .maxAge(authProperties.jwt().accessTokenTtlSeconds())
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    /**
     * 通过 maxAge=0 让浏览器立即删除访问令牌 Cookie。
     */
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
