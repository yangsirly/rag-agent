package yangsirly.rag_agent.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

/**
 * 聊天闭环的 HTTP 集成测试：消息对写入、clientMessageId 幂等三态、
 * 并发双发唯一性、分页与深分页保护、游标分页、会话软删除全流程。
 *
 * <p>这些用例对应需求文档 2.2 的验收标准（50 线程同 key 仅 2 行——
 * 这里用 12 线程在 H2 上验证同一不变量，MySQL 压测见 k6 计划）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ChatFlowIntegrationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void sendCreatesUserAndAssistantMessagePair() throws Exception {
		Cookie cookie = registerAndLogin();
		Long conversationId = createConversation(cookie);

		String clientMessageId = UUID.randomUUID().toString();
		MvcResult result = mockMvc.perform(post("/conversations/{id}/messages", conversationId)
				.cookie(cookie)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"clientMessageId":"%s","content":"你好，第一阶段"}
						""".formatted(clientMessageId)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.statusCode").value(201))
				.andExpect(jsonPath("$.userMessage.role").value("USER"))
				.andExpect(jsonPath("$.userMessage.clientMessageId").value(clientMessageId))
				.andExpect(jsonPath("$.assistantMessage.role").value("ASSISTANT"))
				.andReturn();

		// 模板回复必须是中文契约文本（曾因重构被误改为英文，回归断言）；
		// ASSISTANT 必须回指本次 USER 消息。
		JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
		assertThat(body.path("assistantMessage").path("content").asText())
				.isEqualTo(MessageService.TEMPLATE_REPLY);
		assertThat(body.path("assistantMessage").path("replyToMessageId").asText())
				.isEqualTo(body.path("userMessage").path("id").asText());
		// 数据库恰好一对消息。
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM messages WHERE conversation_id = ?", Integer.class, conversationId);
		assertThat(count).isEqualTo(2);
	}

	@Test
	void retryWithSameClientMessageIdReturnsSamePairWithoutNewRows() throws Exception {
		Cookie cookie = registerAndLogin();
		Long conversationId = createConversation(cookie);

		String clientMessageId = UUID.randomUUID().toString();
		String body = """
				{"clientMessageId":"%s","content":"重试同一条消息"}
				""".formatted(clientMessageId);

		MvcResult first = mockMvc.perform(post("/conversations/{id}/messages", conversationId)
				.cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()).andReturn();
		String firstUserMessageId = objectMapper.readTree(first.getResponse().getContentAsString())
				.path("userMessage").path("id").asText();

		// 网络超时重试：同 key 同内容 → 200 返回原消息对，不新增行。
		mockMvc.perform(post("/conversations/{id}/messages", conversationId)
				.cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.statusCode").value(200))
				.andExpect(jsonPath("$.userMessage.id").value(firstUserMessageId));

		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM messages WHERE conversation_id = ?", Integer.class, conversationId);
		assertThat(count).isEqualTo(2);
	}

	@Test
	void sameClientMessageIdWithDifferentContentConflicts() throws Exception {
		Cookie cookie = registerAndLogin();
		Long conversationId = createConversation(cookie);

		String clientMessageId = UUID.randomUUID().toString();
		mockMvc.perform(post("/conversations/{id}/messages", conversationId)
				.cookie(cookie).contentType(MediaType.APPLICATION_JSON)
				.content("{\"clientMessageId\":\"%s\",\"content\":\"第一次内容\"}".formatted(clientMessageId)))
				.andExpect(status().isCreated());

		// 同 key 不同内容 → 409，不能静默复用首次结果。
		mockMvc.perform(post("/conversations/{id}/messages", conversationId)
				.cookie(cookie).contentType(MediaType.APPLICATION_JSON)
				.content("{\"clientMessageId\":\"%s\",\"content\":\"篡改后的内容\"}".formatted(clientMessageId)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM messages WHERE conversation_id = ?", Integer.class, conversationId);
		assertThat(count).isEqualTo(2);
	}

	@Test
	void concurrentSendWithSameKeyProducesExactlyOnePair() throws Exception {
		Cookie cookie = registerAndLogin();
		Long conversationId = createConversation(cookie);

		String clientMessageId = UUID.randomUUID().toString();
		String body = "{\"clientMessageId\":\"%s\",\"content\":\"并发双发\"}".formatted(clientMessageId);
		int threads = 12;

		ExecutorService executor = Executors.newFixedThreadPool(threads);
		try {
			CountDownLatch ready = new CountDownLatch(threads);
			List<Callable<Integer>> tasks = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				tasks.add(() -> {
					ready.countDown();
					ready.await();
					MvcResult r = mockMvc.perform(post("/conversations/{id}/messages", conversationId)
							.cookie(cookie).contentType(MediaType.APPLICATION_JSON).content(body))
							.andReturn();
					return r.getResponse().getStatus();
				});
			}
			List<Future<Integer>> futures = executor.invokeAll(tasks);
			List<Integer> statuses = new CopyOnWriteArrayList<>();
			for (Future<Integer> f : futures) {
				statuses.add(f.get(30, TimeUnit.SECONDS));
			}

			// 全部请求成功（一个 201，其余 200），没有 500。
			assertThat(statuses).allMatch(s -> s == 200 || s == 201);
			assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);

			// 数据库幂等不变量：同 key 全局只有一对消息。
			Integer count = jdbcTemplate.queryForObject(
					"SELECT COUNT(*) FROM messages WHERE conversation_id = ?",
					Integer.class, conversationId);
			assertThat(count).isEqualTo(2);
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void sendToForeignConversationReturns404() throws Exception {
		Cookie ownerCookie = registerAndLogin();
		Long conversationId = createConversation(ownerCookie);
		Cookie strangerCookie = registerAndLogin();

		mockMvc.perform(post("/conversations/{id}/messages", conversationId)
				.cookie(strangerCookie).contentType(MediaType.APPLICATION_JSON)
				.content("{\"clientMessageId\":\"%s\",\"content\":\"越权发送\"}".formatted(UUID.randomUUID())))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOT_FOUND"));

		// 他人会话消息数不变。
		Integer count = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM messages WHERE conversation_id = ?", Integer.class, conversationId);
		assertThat(count).isEqualTo(0);
	}

	@Test
	void listMessagesPaginatesNewestFirstAndRejectsDeepPagination() throws Exception {
		Cookie cookie = registerAndLogin();
		Long conversationId = createConversation(cookie);

		// 发 6 条消息 → 12 行。
		for (int i = 0; i < 6; i++) {
			sendMessage(cookie, conversationId, UUID.randomUUID().toString(), "消息 " + i);
		}

		// page=0 size=2：返回最新两行，页内时间正序。
		// 最新一次发送产生 USER(id11,"消息 5") + ASSISTANT(id12,模板)；
		// SQL 按 createdAt DESC, id DESC 取 [ASST(12), USER(11)]，内存反转后 [USER(11), ASST(12)]。
		MvcResult page = mockMvc.perform(get("/conversations/{id}/messages", conversationId)
				.cookie(cookie).param("page", "0").param("size", "2"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.totalElements").value(12))
				.andExpect(jsonPath("$.items.length()").value(2))
				.andReturn();
		JsonNode items = objectMapper.readTree(page.getResponse().getContentAsString()).path("items");
		assertThat(items.get(0).path("role").asText()).isEqualTo("USER");
		assertThat(items.get(0).path("content").asText()).isEqualTo("消息 5");
		assertThat(items.get(1).path("role").asText()).isEqualTo("ASSISTANT");

		// 深分页保护：page*size = 1000 → 400。
		mockMvc.perform(get("/conversations/{id}/messages", conversationId)
				.cookie(cookie).param("page", "20").param("size", "50"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_MESSAGE_REQUEST"));
	}

	@Test
	void cursorPaginationReturnsMessagesAfterCursor() throws Exception {
		Cookie cookie = registerAndLogin();
		Long conversationId = createConversation(cookie);

		Long cursor;
		for (int i = 0; i < 3; i++) {
			sendMessage(cookie, conversationId, UUID.randomUUID().toString(), "游标消息 " + i);
		}

		// 取第一条 USER 消息的 id 作为游标（查询数据库最直接）。
		cursor = jdbcTemplate.queryForObject(
				"SELECT id FROM messages WHERE conversation_id = ? AND role = 'USER' ORDER BY id LIMIT 1",
				Long.class, conversationId);

		MvcResult result = mockMvc.perform(get("/conversations/{id}/messages", conversationId)
				.cookie(cookie).param("cursor", String.valueOf(cursor)).param("size", "10"))
				.andExpect(status().isOk())
				.andReturn();
		JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString()).path("items");
		// 游标是第 1 次 USER 的 id；之后还有 5 行：第 1 次的 ASSISTANT +
		// 第 2、3 次各自的 USER+ASSISTANT（createdAt 相同时按 id 递增）。
		assertThat(items.size()).isEqualTo(5);
		// 页内时间正序：USER 消息落在 index 1（"游标消息 1"）和 3（"游标消息 2"）。
		assertThat(items.get(1).path("role").asText()).isEqualTo("USER");
		assertThat(items.get(1).path("content").asText()).isEqualTo("游标消息 1");
		assertThat(items.get(3).path("content").asText()).isEqualTo("游标消息 2");
	}

	@Test
	void deleteConversationSoftDeletesAndSecondDeleteReturns404() throws Exception {
		Cookie cookie = registerAndLogin();
		Long conversationId = createConversation(cookie);
		sendMessage(cookie, conversationId, UUID.randomUUID().toString(), "待删除会话的消息");

		// 首次删除 → 204。
		mockMvc.perform(delete("/conversations/{id}", conversationId).cookie(cookie))
				.andExpect(status().isNoContent());

		// 删除后：会话 404、消息列表 404、再次删除 404。
		mockMvc.perform(get("/conversations/{id}", conversationId).cookie(cookie))
				.andExpect(status().isNotFound());
		mockMvc.perform(get("/conversations/{id}/messages", conversationId).cookie(cookie))
				.andExpect(status().isNotFound());
		mockMvc.perform(delete("/conversations/{id}", conversationId).cookie(cookie))
				.andExpect(status().isNotFound());

		// 软删除验证：行仍在（外键完整），但 deleted_at 已打标，有效行数为 0。
		Integer totalRows = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM messages WHERE conversation_id = ?", Integer.class, conversationId);
		Integer liveRows = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM messages WHERE conversation_id = ? AND deleted_at IS NULL",
				Integer.class, conversationId);
		Integer conversationDeleted = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM conversations WHERE id = ? AND deleted_at IS NOT NULL",
				Integer.class, conversationId);
		assertThat(totalRows).isEqualTo(2);
		assertThat(liveRows).isEqualTo(0);
		assertThat(conversationDeleted).isEqualTo(1);
	}

	@Test
	void renameUpdatesTitleOnlyForOwner() throws Exception {
		Cookie cookie = registerAndLogin();
		Long conversationId = createConversation(cookie);
		Cookie strangerCookie = registerAndLogin();

		mockMvc.perform(patch("/conversations/{id}", conversationId)
				.cookie(strangerCookie).contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"越权改名\"}"))
				.andExpect(status().isNotFound());

		mockMvc.perform(patch("/conversations/{id}", conversationId)
				.cookie(cookie).contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"新标题\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("新标题"));
	}

	@Test
	void invalidClientMessageIdAndHeaderMismatchRejected() throws Exception {
		Cookie cookie = registerAndLogin();
		Long conversationId = createConversation(cookie);

		// 非 UUID 的幂等键 → 400。
		mockMvc.perform(post("/conversations/{id}/messages", conversationId)
				.cookie(cookie).contentType(MediaType.APPLICATION_JSON)
				.content("{\"clientMessageId\":\"not-a-uuid\",\"content\":\"非法键\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_MESSAGE_REQUEST"));

		// Header 与 body 不一致 → 400。
		mockMvc.perform(post("/conversations/{id}/messages", conversationId)
				.cookie(cookie).contentType(MediaType.APPLICATION_JSON)
				.header("Idempotency-Key", UUID.randomUUID())
				.content("{\"clientMessageId\":\"%s\",\"content\":\"键不一致\"}".formatted(UUID.randomUUID())))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_MESSAGE_REQUEST"));
	}

	// ------------------------------------------------------------------
	// 辅助方法
	// ------------------------------------------------------------------

	private Cookie registerAndLogin() throws Exception {
		String email = "chat-" + UUID.randomUUID() + "@example.com";
		String password = "password-ok-1";
		mockMvc.perform(post("/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
				.andExpect(status().isCreated());

		MvcResult login = mockMvc.perform(post("/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
				.andExpect(status().isOk()).andReturn();
		Cookie cookie = login.getResponse().getCookie("access_token");
		assertThat(cookie).isNotNull();
		return cookie;
	}

	private Long createConversation(Cookie cookie) throws Exception {
		MvcResult result = mockMvc.perform(post("/conversations")
				.cookie(cookie).contentType(MediaType.APPLICATION_JSON)
				.content("{\"title\":\"测试会话\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.title").value("测试会话"))
				.andReturn();
		return Long.valueOf(objectMapper.readTree(result.getResponse().getContentAsString()).path("id").asText());
	}

	private MvcResult sendMessage(Cookie cookie, Long conversationId, String clientMessageId, String content)
			throws Exception {
		return mockMvc.perform(post("/conversations/{id}/messages", conversationId)
				.cookie(cookie).contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"clientMessageId":"%s","content":"%s"}
						""".formatted(clientMessageId, content)))
				.andExpect(status().isCreated())
				.andReturn();
	}
}
