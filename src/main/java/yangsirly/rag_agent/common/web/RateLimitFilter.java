package yangsirly.rag_agent.common.web;

import java.io.IOException;
import java.time.Duration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import yangsirly.rag_agent.common.exception.RateLimitExceededException;
import yangsirly.rag_agent.common.ratelimit.RateLimitProperties;
import yangsirly.rag_agent.common.ratelimit.RateLimiter;

/**
 * 注册/登录接口的 IP 级限流过滤器。
 *
 * <p>
 * 作为 Servlet 过滤器注册（@Order 紧随 TraceIdFilter 之后、
 * Spring Security 之前），可在未认证流量进入安全链和业务层之前先行拒绝，
 * 这是"Filter 最前置"的本意。
 * </p>
 *
 * <p>
 * 注意：这里只做 IP 维度限流。账号维度防刷（连续失败锁定）由
 * {@code AuthService} 在解析 JSON body 之后处理——在 Filter 里读取
 * JSON 请求体会消费 InputStream，需要缓存包装，复杂度不值当。
 * </p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiter rateLimiter;
    private final RateLimitProperties props;

    public RateLimitFilter(RateLimiter rateLimiter, RateLimitProperties props) {
        this.rateLimiter = rateLimiter;
        this.props = props;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String method = request.getMethod();
        String ip = clientIp(request);

        // 只拦截匿名即可调用的入口：注册与登录。发送消息的限流在
        // MessageService 中按 userId 维度执行（需要认证主体）。
        try {
            if ("POST".equals(method) && "/register".equals(path)) {
                check("register:ip:" + ip, props.registerPerIpPerMinute());
            } else if ("POST".equals(method) && "/login".equals(path)) {
                check("login:ip:" + ip, props.loginPerIpPerMinute());
            }
        } catch (RateLimitExceededException ex) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(ex.getRetryAfterSeconds()));
            if (ex.getLimit() > 0) {
                response.setHeader("X-RateLimit-Limit", String.valueOf(ex.getLimit()));
            }
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"statusCode\":429,\"code\":\"RATE_LIMITED\",\"message\":\"Too many requests\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * 执行一次固定窗口限流检查，超限时抛 429 异常。
     */
    private void check(String key, int limit) {
        if (!rateLimiter.tryAcquire(key, limit, Duration.ofMinutes(1))) {
            throw new RateLimitExceededException("Rate limit exceeded for " + key, 60, limit);
        }
    }

    /**
     * 客户端 IP。默认只用 remoteAddr；仅当配置声明"部署在可信代理之后"
     * （app.rate-limit.trust-forwarded-for=true）才信任 X-Forwarded-For，
     * 否则攻击者可以伪造该头为每个请求换一个"IP"绕过限流。
     */
    private String clientIp(HttpServletRequest request) {
        if (props.trustForwardedFor()) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
