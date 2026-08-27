package yangsirly.rag_agent.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import yangsirly.rag_agent.common.ratelimit.InMemoryRateLimiter;
import yangsirly.rag_agent.common.ratelimit.RateLimitProperties;

/**
 * RateLimitFilter 的行为单元测试（直接构造 Filter，不起 Spring 上下文）：
 * 超限返回 429 + Retry-After + X-RateLimit-Limit；未超限放行。
 */
class RateLimitFilterTests {

    private static RateLimitProperties props() {
        return new RateLimitProperties(10, 3, 5, 20, false);
    }

    @Test
    void loginBeyondIpLimitReturns429WithHeaders() throws Exception {
		RateLimitFilter filter = new RateLimitFilter(new InMemoryRateLimiter(), props());
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
		request.setRemoteAddr("10.0.0.1");
		request.setContentType("application/json");

		// 前 3 次放行，第 4 次 429。MockFilterChain 每次新建。
		for (int i = 0; i < 3; i++) {
			filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
		}

        MockHttpServletResponse limited = new MockHttpServletResponse();
        MockFilterChain untouched = new MockFilterChain();
        filter.doFilter(request, limited, untouched);
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isEqualTo("60");
        // X-RateLimit-Limit 必须是真实的阈值（曾硬编码为 60）。
        assertThat(limited.getHeader("X-RateLimit-Limit")).isEqualTo("3");
        assertThat(limited.getContentAsString()).contains("RATE_LIMITED");
        // 被限流的请求不得进入后续链。
        assertThat(untouched.getRequest()).isNull();
    }

    @Test
    void registerUsesSeparateBucketFromLogin() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(new InMemoryRateLimiter(), props());

        MockHttpServletRequest login = new MockHttpServletRequest("POST", "/login");
        login.setRemoteAddr("10.0.0.2");
        MockHttpServletRequest register = new MockHttpServletRequest("POST", "/register");
        register.setRemoteAddr("10.0.0.2");

        // 打满 login 桶（3 次）。
        for (int i = 0; i < 3; i++) {
            filter.doFilter(login, new MockHttpServletResponse(), new MockFilterChain());
        }
        MockHttpServletResponse loginBlocked = new MockHttpServletResponse();
        filter.doFilter(login, loginBlocked, new MockFilterChain());
        assertThat(loginBlocked.getStatus()).isEqualTo(429);

        // register 是独立桶（阈值 10），不受 login 影响。
        MockHttpServletResponse registerOk = new MockHttpServletResponse();
        filter.doFilter(register, registerOk, new MockFilterChain());
        assertThat(registerOk.getStatus()).isEqualTo(200);
    }

    @Test
    void otherPathsAreNotRateLimited() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(new InMemoryRateLimiter(), props());
        // 发送消息路径不在 IP 限流范围（在 Service 内按 userId 限流）。
        MockHttpServletRequest send = new MockHttpServletRequest("POST", "/conversations/1/messages");
        send.setRemoteAddr("10.0.0.3");
        for (int i = 0; i < 50; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(send, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void forwardedForIgnoredUnlessTrusted() throws Exception {
        // 默认不信任 X-Forwarded-For：伪造头不能改变限流键。
        RateLimitProperties untrusted = props();
        RateLimitFilter filter = new RateLimitFilter(new InMemoryRateLimiter(), untrusted);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.setRemoteAddr("10.0.0.4");
        for (int i = 0; i < 3; i++) {
            request.addHeader("X-Forwarded-For", "1.2.3." + i);
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        }
        MockHttpServletResponse blocked = new MockHttpServletResponse();
        filter.doFilter(request, blocked, new MockFilterChain());
        assertThat(blocked.getStatus()).isEqualTo(429);

        // 信任代理后：不同 XFF 视为不同客户端，各自独立桶。
        RateLimitProperties trusted = new RateLimitProperties(10, 3, 5, 20, true);
        RateLimitFilter trustedFilter = new RateLimitFilter(new InMemoryRateLimiter(), trusted);
        for (int i = 0; i < 5; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/login");
            req.setRemoteAddr("10.0.0.5");
            req.addHeader("X-Forwarded-For", "9.9.9." + i);
            MockHttpServletResponse response = new MockHttpServletResponse();
            trustedFilter.doFilter(req, response, new MockFilterChain());
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }
}
