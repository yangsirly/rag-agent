package yangsirly.rag_agent.registration;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 把注册流程抛出的异常统一转换为 JSON 错误响应。
 *
 * <p>{@code assignableTypes} 把作用范围限制在注册 Controller，避免登录等其他模块
 * 的 {@code MethodArgumentNotValidException} 被错误映射成 {@code INVALID_REGISTER_REQUEST}。</p>
 */
@RestControllerAdvice(assignableTypes = RegisterController.class)
public class RegistrationExceptionHandler {

	/**
	 * 处理 {@code @Valid} 校验失败。
	 *
	 * <p>当请求字段不满足 {@code @NotBlank} 等约束时，Spring 会在进入
	 * Controller 方法之前抛出 MethodArgumentNotValidException，因此无效请求
	 * 不会继续调用 RegisterService。</p>
	 */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiErrorResponse> handleValidationFailure(MethodArgumentNotValidException exception) {
		// 当前接口对外返回统一提示，不暴露框架内部的字段校验异常结构。
		ApiErrorResponse response = new ApiErrorResponse(
				HttpStatus.BAD_REQUEST.value(),
				"INVALID_REGISTER_REQUEST",
				"Registration request fields must not be empty");
		// ResponseEntity 同时控制 HTTP 状态码和响应体。
		return ResponseEntity.badRequest().body(response);
	}

	/** 重复邮箱是当前资源状态冲突，因此返回 HTTP 409。 */
	@ExceptionHandler(EmailAlreadyRegisteredException.class)
	public ResponseEntity<ApiErrorResponse> handleEmailAlreadyRegistered(EmailAlreadyRegisteredException exception) {
		ApiErrorResponse response = new ApiErrorResponse(
				HttpStatus.CONFLICT.value(),
				"EMAIL_ALREADY_REGISTERED",
				exception.getMessage());
		return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
	}
}
