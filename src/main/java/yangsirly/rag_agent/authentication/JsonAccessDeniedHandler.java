package yangsirly.rag_agent.authentication;

import java.io.IOException;

import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import yangsirly.rag_agent.registration.ApiErrorResponse;

/**
 * 已登录但权限不足时返回统一 JSON 403。
 *
 * <p>例如 CUSTOMER 调用仅 EDITOR 可用的知识库管理接口。
 * 当前骨干先把响应契约固定下来，具体授权规则在后续里程碑补充。</p>
 *
 * <p>Spring Boot 4 使用 Jackson 3，包名为 {@code tools.jackson.databind}。</p>
 */
@Component
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

	private final ObjectMapper objectMapper;

	public JsonAccessDeniedHandler(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public void handle(
			HttpServletRequest request,
			HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException {
		response.setStatus(HttpStatus.FORBIDDEN.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		ApiErrorResponse body = new ApiErrorResponse(
				HttpStatus.FORBIDDEN.value(),
				"FORBIDDEN",
				"Access is denied");
		objectMapper.writeValue(response.getOutputStream(), body);
	}
}
