package yangsirly.rag_agent.authentication;

import java.io.IOException;

import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import yangsirly.rag_agent.registration.ApiErrorResponse;

/**
 * 未登录访问受保护资源时返回统一 JSON 401。
 *
 * <p>Spring Security 默认可能返回 HTML 或空响应体；前后端分离项目需要稳定的
 * {@link ApiErrorResponse}，前端才能据此清理本地状态并跳转登录页。</p>
 *
 * <p>Spring Boot 4 使用 Jackson 3，包名为 {@code tools.jackson.databind}，
 * 不再是旧的 {@code com.fasterxml.jackson.databind}。</p>
 */
@Component
public class JsonUnauthorizedAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final ObjectMapper objectMapper;

	public JsonUnauthorizedAuthenticationEntryPoint(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	@Override
	public void commence(
			HttpServletRequest request,
			HttpServletResponse response,
			AuthenticationException authException) throws IOException {
		response.setStatus(HttpStatus.UNAUTHORIZED.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		ApiErrorResponse body = new ApiErrorResponse(
				HttpStatus.UNAUTHORIZED.value(),
				"UNAUTHORIZED",
				"Authentication is required");
		objectMapper.writeValue(response.getOutputStream(), body);
	}
}
