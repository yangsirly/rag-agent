package yangsirly.rag_agent.authentication;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
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

/** 认证接口入口：登录、刷新、登出、当前用户信息。 */
@RestController
public class AuthController {

    private final AuthService authService;
    private final RefreshSessionService refreshSessionService;
    private final AuthProperties authProperties;
    private final Clock clock;

    public AuthController(AuthService authService, RefreshSessionService refreshSessionService,
            AuthProperties authProperties, Clock clock) {
        this.authService = authService;
        this.refreshSessionService = refreshSessionService;
        this.authProperties = authProperties;
        this.clock = clock;
    }

    /** 密码登录：校验成功后建立设备会话并写入两个 HttpOnly Cookie。 */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletResponse response) {
        LoginCommand command = new LoginCommand(request.email(), request.password());
        AuthService.LoginResult result = authService.login(command);
        writeTokenCookies(response, result.tokens());
        return ResponseEntity.ok(new LoginResponse(
                HttpStatus.OK.value(),
                result.user().role().name()));
    }

    /**
     * 使用一次性 Refresh Token 轮换两个凭证。
     *
     * <p>无效 Refresh 不把具体原因返回给客户端；清除 Cookie 后由统一异常处理器
     * 输出 401，前端再回到登录页。</p>
     */
    @PostMapping("/refresh")
    public ResponseEntity<RefreshResponse> refresh(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = extractCookie(request, authProperties.cookie().refreshName());
        try {
            TokenPair tokens = refreshSessionService.refresh(refreshToken);
            writeTokenCookies(response, tokens);
            return ResponseEntity.ok(new RefreshResponse(HttpStatus.OK.value()));
        } catch (InvalidRefreshTokenException exception) {
            // 缺失、畸形、过期、撤销和重放都统一清除两个 Cookie，避免客户端
            // 继续携带半失效的凭证；前端用请求 epoch 防止迟到的匿名响应覆盖新登录态。
            clearTokenCookies(response);
            throw exception;
        }
    }

    /** 登出：拉黑当前 Access、撤销当前设备 Refresh 会话，并清除两个 Cookie。 */
    @PostMapping("/logout")
    public ResponseEntity<LogoutResponse> logout(HttpServletRequest request, HttpServletResponse response) {
        String accessToken = extractCookie(request, authProperties.cookie().name());
        String refreshToken = extractCookie(request, authProperties.cookie().refreshName());
        try {
            refreshSessionService.revoke(refreshToken, accessToken);
        } catch (RuntimeException ignored) {
            // logout 必须幂等；Cookie 清除不应被 Redis/数据库短暂故障阻断。
        }
        clearTokenCookies(response);
        return ResponseEntity.ok(new LogoutResponse(HttpStatus.OK.value()));
    }

    /** 返回当前登录用户的最小信息视图。 */
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

    private String extractCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null || name == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private void writeTokenCookies(HttpServletResponse response, TokenPair tokens) {
        AuthProperties.Cookie cookieConfig = authProperties.cookie();
        addCookie(response, cookieConfig.name(), tokens.accessToken().value(),
                authProperties.jwt().accessTokenTtlSeconds(), cookieConfig);
        Duration refreshRemaining = Duration.between(
                LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), tokens.refreshExpiresAt());
        // Max-Age 向上取整，避免纳秒级的响应处理时间把登录 Cookie 提前截成 604799；
        // 同时封顶于配置 TTL，轮换不会把客户端 Cookie 延长到固定绝对期限之外。
        long refreshMaxAge = Math.min(authProperties.jwt().refreshTokenTtlSeconds(),
                Math.max(0, (refreshRemaining.toMillis() + 999) / 1000));
        addCookie(response, cookieConfig.refreshName(), tokens.refreshToken(), refreshMaxAge, cookieConfig);
    }

    private void addCookie(HttpServletResponse response, String name, String value, long maxAge,
            AuthProperties.Cookie cookieConfig) {
        ResponseCookie cookie = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(cookieConfig.secure())
                .sameSite(cookieConfig.sameSite())
                .path(cookieConfig.path())
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearTokenCookies(HttpServletResponse response) {
        AuthProperties.Cookie cookieConfig = authProperties.cookie();
        addCookie(response, cookieConfig.name(), "", 0, cookieConfig);
        addCookie(response, cookieConfig.refreshName(), "", 0, cookieConfig);
    }
}
