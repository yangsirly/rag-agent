package yangsirly.rag_agent.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.security.core.context.SecurityContextHolder;

import jakarta.servlet.http.Cookie;

/**
 * 在真实 MySQL/InnoDB 上验证 Refresh 行锁和严格重放语义。
 *
 * <p>该测试默认跳过，只有显式设置 {@code RUN_MYSQL_TESTS=true} 才会连接外部数据库。
 * H2 测试覆盖业务分支，但不能替代这里对 {@code SELECT ... FOR UPDATE} 的验证。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("mysql")
@EnabledIfEnvironmentVariable(named = "RUN_MYSQL_TESTS", matches = "true")
class MySqlRefreshSessionConcurrencyIntegrationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private Flyway flyway;

	private String testEmail;

	@AfterEach
	void cleanUpTestUser() {
		if (testEmail != null) {
			jdbcTemplate.update("DELETE rs FROM refresh_sessions rs JOIN users u ON u.id = rs.user_id WHERE u.email = ?",
					testEmail);
			jdbcTemplate.update("DELETE FROM users WHERE email = ?", testEmail);
		}
	}

	@Test
	void concurrentRefreshWithSameTokenHasOneWinnerThenRevokesSession() throws Exception {
		try (Connection connection = dataSource.getConnection()) {
			assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
		}
		assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("6");

		testEmail = "mysql-refresh-" + UUID.randomUUID() + "@example.com";
		String password = "Test-pass-123";
		registerUser(testEmail, password);
		MvcResult login = mockMvc.perform(post("/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginBody(testEmail, password)))
				.andExpect(status().isOk())
				.andReturn();
		Cookie access = requiredCookie(login, "access_token");
		Cookie refresh = requiredCookie(login, "refresh_token");

		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			List<Future<MvcResult>> futures = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				futures.add(executor.submit(() -> {
					SecurityContextHolder.clearContext();
					start.await();
					try {
						return mockMvc.perform(post("/refresh").cookie(copy(refresh), copy(access))).andReturn();
					} finally {
						SecurityContextHolder.clearContext();
					}
				}));
			}
			start.countDown();
			MvcResult first = futures.get(0).get();
			MvcResult second = futures.get(1).get();
			List<Integer> statuses = List.of(first.getResponse().getStatus(), second.getResponse().getStatus());
			assertThat(statuses).containsExactlyInAnyOrder(200, 401);

			MvcResult winner = first.getResponse().getStatus() == 200 ? first : second;
			Cookie rotatedAccess = requiredCookie(winner, "access_token");
			SecurityContextHolder.clearContext();
			mockMvc.perform(get("/me").cookie(rotatedAccess))
					.andExpect(status().isUnauthorized());

			String sessionId = refresh.getValue().substring(0, 36);
			Integer revoked = jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM refresh_sessions WHERE id = ? AND revoked_at IS NOT NULL",
					Integer.class, sessionId);
			assertThat(revoked).isEqualTo(1);
		} finally {
			executor.shutdownNow();
		}
	}

	private void registerUser(String email, String password) throws Exception {
		mockMvc.perform(post("/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content(loginBody(email, password)))
				.andExpect(status().isCreated());
	}

	private static Cookie requiredCookie(MvcResult result, String name) {
		Cookie cookie = result.getResponse().getCookie(name);
		assertThat(cookie).as(name + " cookie").isNotNull();
		return cookie;
	}

	private static Cookie copy(Cookie cookie) {
		return new Cookie(cookie.getName(), cookie.getValue());
	}

	private static String loginBody(String email, String password) {
		return """
				{"email":"%s","password":"%s"}
				""".formatted(email, password);
	}
}
