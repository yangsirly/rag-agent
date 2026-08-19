package yangsirly.rag_agent.authentication;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import jakarta.servlet.http.Cookie;

/**
 * 认证模块的 HTTP 边界集成测试。
 *
 * <p>覆盖：登录成功写 Cookie、错误密码 / 不存在账号统一 401、
 * 禁用用户登录拒绝、校验错误、匿名 401、退出清 Cookie，以及登录后禁用再调 /me。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void rejectsStructurallyIncompleteLoginRequest() throws Exception {
		// 空 JSON 缺少 @NotBlank 字段，应在进入 Controller 方法前返回 400。
		mockMvc.perform(post("/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_LOGIN_REQUEST"));
	}

	@Test
	void returnsUnauthorizedJsonForAnonymousProtectedRequest() throws Exception {
		// 任意未放行的路径默认需要认证；用于验证过滤器链与 JSON 401 入口已接通。
		mockMvc.perform(get("/api/protected-probe"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	void meReturnsUnauthorizedWhenAnonymous() throws Exception {
		// GET /me 需登录；匿名访问应 401，与前端 bootstrap 未登录路径一致。
		mockMvc.perform(get("/me"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	void logoutClearsAccessTokenCookie() throws Exception {
		// 即使当前未登录，退出也应返回 200，并下发 Max-Age=0 的同名 Cookie。
		mockMvc.perform(post("/logout"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.statusCode").value(200))
				.andExpect(header().string("Set-Cookie", Matchers.containsString("access_token=")))
				.andExpect(header().string("Set-Cookie", Matchers.containsString("Max-Age=0")));
	}

	@Test
	void loginSucceedsAndWritesAccessTokenCookie() throws Exception {
		String email = "login-ok-" + UUID.randomUUID() + "@example.com";
		String password = "password-ok-1";
		registerUser(email, password);

		MvcResult login = mockMvc.perform(post("/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginBody(email, password)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.statusCode").value(200))
				.andExpect(jsonPath("$.role").value("CUSTOMER"))
				// 凭证只在 Cookie，不出现在 JSON 体（LoginResponse 仅 statusCode + role）
				.andExpect(jsonPath("$.accessToken").doesNotExist())
				.andExpect(jsonPath("$.token").doesNotExist())
				.andExpect(header().string("Set-Cookie", Matchers.containsString("access_token=")))
				// 成功登录不应下发“立即删除”的 Cookie
				.andExpect(header().string("Set-Cookie", Matchers.not(Matchers.containsString("Max-Age=0"))))
				.andReturn();

		Cookie cookie = login.getResponse().getCookie("access_token");
		org.assertj.core.api.Assertions.assertThat(cookie).isNotNull();
		org.assertj.core.api.Assertions.assertThat(cookie.getValue()).isNotBlank();
	}

	@Test
	void loginRejectsWrongPasswordWithInvalidCredentials() throws Exception {
		String email = "login-wrong-pw-" + UUID.randomUUID() + "@example.com";
		registerUser(email, "password-ok-1");

		mockMvc.perform(post("/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginBody(email, "definitely-wrong")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
				// 失败路径不应签发可用登录 Cookie
				.andExpect(header().doesNotExist("Set-Cookie"));
	}

	@Test
	void loginRejectsUnknownEmailWithSameInvalidCredentialsCode() throws Exception {
		// 与“密码错误”同一错误码，避免通过差异信息枚举账号是否存在。
		mockMvc.perform(post("/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginBody("missing-" + UUID.randomUUID() + "@example.com", "password-ok-1")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
				.andExpect(header().doesNotExist("Set-Cookie"));
	}

	@Test
	void loginRejectsDisabledUser() throws Exception {
		String email = "login-disabled-" + UUID.randomUUID() + "@example.com";
		String password = "password-ok-1";
		registerUser(email, password);

		// 注册默认 ACTIVE；直接改库模拟运营禁用。
		jdbcTemplate.update("UPDATE users SET status = 'DISABLED' WHERE email = ?", email);

		// 密码正确仍应拒绝，且错误码区别于凭证错误。
		mockMvc.perform(post("/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginBody(email, password)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("USER_DISABLED"))
				.andExpect(header().doesNotExist("Set-Cookie"));
	}

	@Test
	void loginThenMeReturnsCurrentUserFromDatabase() throws Exception {
		String email = "me-it-" + UUID.randomUUID() + "@example.com";
		String password = "password-ok-1";
		registerUser(email, password);

		Cookie cookie = loginAndGetAccessTokenCookie(email, password);

		mockMvc.perform(get("/me").cookie(cookie))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.statusCode").value(200))
				.andExpect(jsonPath("$.email").value(email))
				.andExpect(jsonPath("$.role").value("CUSTOMER"))
				.andExpect(jsonPath("$.userId").value(Matchers.matchesPattern("\\d+")));
	}

	@Test
	void meReturnsUnauthorizedWhenUserDisabledAfterLogin() throws Exception {
		String email = "me-disabled-" + UUID.randomUUID() + "@example.com";
		String password = "password-ok-1";
		registerUser(email, password);

		Cookie cookie = loginAndGetAccessTokenCookie(email, password);

		// JWT 仍有效，但库里已禁用 → Service 抛 CurrentUserUnavailableException → 401
		jdbcTemplate.update("UPDATE users SET status = 'DISABLED' WHERE email = ?", email);

		mockMvc.perform(get("/me").cookie(cookie))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	private void registerUser(String email, String password) throws Exception {
		mockMvc.perform(post("/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"email":"%s","password":"%s"}
						""".formatted(email, password)))
				.andExpect(status().isCreated());
	}

	private Cookie loginAndGetAccessTokenCookie(String email, String password) throws Exception {
		MvcResult login = mockMvc.perform(post("/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginBody(email, password)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("CUSTOMER"))
				.andExpect(header().string("Set-Cookie", Matchers.containsString("access_token=")))
				.andReturn();

		Cookie cookie = login.getResponse().getCookie("access_token");
		org.assertj.core.api.Assertions.assertThat(cookie).isNotNull();
		org.assertj.core.api.Assertions.assertThat(cookie.getValue()).isNotBlank();
		return cookie;
	}

	private static String loginBody(String email, String password) {
		return """
				{"email":"%s","password":"%s"}
				""".formatted(email, password);
	}
}
