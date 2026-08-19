package yangsirly.rag_agent.registration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 从 HTTP 边界验证注册接口的集成测试。
 *
 * <p>{@code @SpringBootTest} 加载真实 Spring 容器，{@code @AutoConfigureMockMvc}
 * 创建 MockMvc。MockMvc 可以模拟 HTTP 请求，但不会真正监听网络端口。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class RegisterControllerTests {

	// 测试对象由 Spring 注入，用来向 Controller 发起模拟请求。
	@Autowired
	private MockMvc mockMvc;

	@Test
	void registersUser() throws Exception {
		mockMvc.perform(post("/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "email": "new-user@example.com",
						  "password": "password"
						}
						"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.statusCode").value(201));
	}

	@Test
	void returnsConflictWhenEmailIsAlreadyRegistered() throws Exception {
		String requestBody = """
				{
				  "email": "duplicate@example.com",
				  "password": "password"
				}
				""";

		// 第一次请求先真实落库，第二次由数据库唯一约束拒绝。
		mockMvc.perform(post("/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
				.andExpect(status().isCreated());

		mockMvc.perform(post("/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content(requestBody))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
	}

	@Test
	void rejectsStructurallyIncompleteRequestBeforeCoreFlow() throws Exception {
		// 空 JSON 缺少两个 @NotBlank 字段，应在进入 Controller 方法前校验失败。
		mockMvc.perform(post("/register")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
				// 同时检查状态码和业务错误码，避免只验证响应的其中一部分。
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REGISTER_REQUEST"));
	}
}
