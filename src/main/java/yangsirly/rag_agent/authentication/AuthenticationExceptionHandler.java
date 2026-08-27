package yangsirly.rag_agent.authentication;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import yangsirly.rag_agent.common.exception.RateLimitExceededException;
import yangsirly.rag_agent.registration.ApiErrorResponse;

/**
 * 把认证流程抛出的异常统一转换为 JSON 错误响应。
 *
 * <p>{@code assignableTypes} 把本处理器限制在认证相关 Controller，
 * 避免与注册模块的校验错误码互相覆盖。</p>
 */
@RestControllerAdvice(assignableTypes = AuthController.class)
public class AuthenticationExceptionHandler {

	/**
	 * 处理登录请求结构校验失败。
	 *
	 * <p>当 email 或 password 为空时，Spring 在进入 Controller 方法前抛出
	 * MethodArgumentNotValidException，因此无效请求不会进入登录 Service。</p>
	 */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiErrorResponse> handleValidationFailure(MethodArgumentNotValidException exception) {
		ApiErrorResponse response = new ApiErrorResponse(
				HttpStatus.BAD_REQUEST.value(),
				"INVALID_LOGIN_REQUEST",
				"Login request fields must not be empty");
		return ResponseEntity.badRequest().body(response);
	}

	/**
	 * 账号或密码错误：统一 401，不区分“账号不存在”与“密码错误”。
	 */
	@ExceptionHandler(InvalidCredentialsException.class)
	public ResponseEntity<ApiErrorResponse> handleInvalidCredentials(InvalidCredentialsException exception) {
		ApiErrorResponse response = new ApiErrorResponse(
				HttpStatus.UNAUTHORIZED.value(),
				"INVALID_CREDENTIALS",
				exception.getMessage());
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
	}

	/** 被禁用用户不能登录。 */
	@ExceptionHandler(UserDisabledException.class)
	public ResponseEntity<ApiErrorResponse> handleUserDisabled(UserDisabledException exception) {
		ApiErrorResponse response = new ApiErrorResponse(
				HttpStatus.UNAUTHORIZED.value(),
				"USER_DISABLED",
				exception.getMessage());
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
	}

	/**
	 * 账号被防刷锁定（连续失败达到阈值）：429 + Retry-After。
	 *
	 * <p>注意必须在这里显式映射，否则 RateLimitExceededException 会落进
	 * 默认 500——它是 RuntimeException，MVC 不会自动转成 429。</p>
	 */
	@ExceptionHandler(RateLimitExceededException.class)
	public ResponseEntity<ApiErrorResponse> handleRateLimited(RateLimitExceededException exception) {
		ApiErrorResponse response = new ApiErrorResponse(
				429,
				"RATE_LIMITED",
				"Too many failed login attempts, account is temporarily locked");
		return ResponseEntity.status(429)
				.header("Retry-After", String.valueOf(exception.getRetryAfterSeconds()))
				.body(response);
	}

	/**
	 * {@code GET /me} 主体缺失、用户已删或已禁用：与“未登录”一样返回 401。
	 *
	 * <p>错误码用 {@code UNAUTHORIZED}，与契约及匿名访问过滤器链的行为一致；
	 * 前端 bootstrap 统一按 401 清本地登录态即可。</p>
	 */
	@ExceptionHandler(CurrentUserUnavailableException.class)
	public ResponseEntity<ApiErrorResponse> handleCurrentUserUnavailable(
			CurrentUserUnavailableException exception) {
		ApiErrorResponse response = new ApiErrorResponse(
				HttpStatus.UNAUTHORIZED.value(),
				"UNAUTHORIZED",
				exception.getMessage());
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
	}
}
