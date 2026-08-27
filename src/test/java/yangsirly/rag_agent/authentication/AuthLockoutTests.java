package yangsirly.rag_agent.authentication;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 登录防刷锁定测试：连续失败达到阈值后，账号被锁定一段时间，
 * 锁定期内即使密码正确也返回 429 + Retry-After（而不是 500）。
 *
 * <p>单独的 @SpringBootTest 上下文：锁定阈值覆盖为 2，
 * 不影响共享上下文的其他测试类（默认阈值 100）。</p>
 */
@SpringBootTest(properties = {
        "app.auth.lock-threshold=2",
        "app.auth.lock-duration-minutes=15"
})
@AutoConfigureMockMvc
class AuthLockoutTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void consecutiveFailuresLockAccountAndReturn429() throws Exception {
		String email = "lock-" + UUID.randomUUID() + "@example.com";
		String password = "password-ok-1";
		registerUser(email, password);

		String wrongBody = """
				{"email":"%s","password":"definitely-wrong"}
				""".formatted(email);

		// 前两次失败：401 INVALID_CREDENTIALS。
		mockMvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON).content(wrongBody))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
		mockMvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON).content(wrongBody))
				.andExpect(status().isUnauthorized());

		// 达到阈值后：即使密码正确也 429 + Retry-After，绝不能是 500。
		mockMvc.perform(post("/login").contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"email":"%s","password":"%s"}
						""".formatted(email, password)))
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.code").value("RATE_LIMITED"))
				.andExpect(header().string("Retry-After", "60"));

		// DB 落了锁定标记。
		// 注意：不写 lock_until > NOW()——Clock.systemUTC() 让时间戳存成 UTC，
		// 而 H2 的 NOW() 返回本地时区（UTC+8），直接比较会因时区错位恒为假。
		// "在未来"已由上面的 429（login 的 isAfter 检查，纯 Java 比较）证明。
		Integer locked = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM users WHERE email = ? AND lock_until IS NOT NULL",
				Integer.class, email);
		org.assertj.core.api.Assertions.assertThat(locked).isEqualTo(1);
	}

	private void registerUser(String email, String password) throws Exception {
		mockMvc.perform(post("/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"email":"%s","password":"%s"}
						""".formatted(email, password)))
				.andExpect(status().isCreated());
	}
}
