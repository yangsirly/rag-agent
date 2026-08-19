package yangsirly.rag_agent.chat;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import yangsirly.rag_agent.registration.ApiErrorResponse;

/**
 * 会话与消息模块的统一异常映射。
 *
 * <p>{@code assignableTypes} 限定在聊天相关 Controller，
 * 避免与注册/登录的 {@code INVALID_*_REQUEST} 错误码互相覆盖。</p>
 *
 * <p>骨架阶段已固定错误码契约；Service 实现后直接抛对应异常即可接通。</p>
 */
@RestControllerAdvice(assignableTypes = {
		ConversationController.class,
		MessageController.class
})
public class ChatExceptionHandler {

	/**
	 * Bean Validation 失败（如 SendMessageRequest 缺字段）。
	 *
	 * <p>按抛出位置区分会话 / 消息错误码较困难，这里统一：
	 * 消息发送校验用 INVALID_MESSAGE_REQUEST；
	 * 会话创建若后续加 @Valid 可再拆分处理器方法。</p>
	 */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
		// 默认按消息请求处理；会话 PATCH 等可在实现时按 path 或注解细化
		ApiErrorResponse body = new ApiErrorResponse(
				HttpStatus.BAD_REQUEST.value(),
				"INVALID_MESSAGE_REQUEST",
				"Message request fields are invalid");
		return ResponseEntity.badRequest().body(body);
	}

	/**
	 * 业务层参数非法：标题/正文长度、UUID 形态、分页参数、路径 id 等。
	 *
	 * <p>路径 id 非法 → INVALID_PATH_PARAMETER；
	 * 其余按消息前缀约定映射（实现阶段可引入专用异常类型以消除字符串判断）。</p>
	 */
	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException exception) {
		String message = exception.getMessage() == null ? "" : exception.getMessage();
		String code;
		String clientMessage;
		if (message.contains("path parameter") || message.contains("Path parameter")) {
			code = "INVALID_PATH_PARAMETER";
			clientMessage = "Path id must be a decimal long";
		}
		else if (message.toLowerCase().contains("title")
				|| message.toLowerCase().contains("conversation")) {
			code = "INVALID_CONVERSATION_REQUEST";
			clientMessage = "Conversation request is invalid";
		}
		else {
			code = "INVALID_MESSAGE_REQUEST";
			clientMessage = "Message request is invalid";
		}
		ApiErrorResponse body = new ApiErrorResponse(
				HttpStatus.BAD_REQUEST.value(),
				code,
				clientMessage);
		return ResponseEntity.badRequest().body(body);
	}

	/** 会话不存在或非本人 → 统一 404，不泄露归属信息。 */
	@ExceptionHandler(ConversationNotFoundException.class)
	public ResponseEntity<ApiErrorResponse> handleNotFound(ConversationNotFoundException exception) {
		ApiErrorResponse body = new ApiErrorResponse(
				HttpStatus.NOT_FOUND.value(),
				"NOT_FOUND",
				exception.getMessage());
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
	}

	/** 同一 clientMessageId 复用于不同 content。 */
	@ExceptionHandler(IdempotencyConflictException.class)
	public ResponseEntity<ApiErrorResponse> handleIdempotencyConflict(IdempotencyConflictException exception) {
		ApiErrorResponse body = new ApiErrorResponse(
				HttpStatus.CONFLICT.value(),
				"IDEMPOTENCY_CONFLICT",
				exception.getMessage());
		return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
	}

	/**
	 * 骨架阶段：未实现方法抛出 UnsupportedOperationException 时返回 501，
	 * 避免被全局 500 处理器吞掉后难以区分“未实现”与“实现 bug”。
	 *
	 * <p>实现完成后应删除本处理器或确保业务路径不再抛该异常。</p>
	 */
	@ExceptionHandler(UnsupportedOperationException.class)
	public ResponseEntity<ApiErrorResponse> handleNotImplemented(UnsupportedOperationException exception) {
		ApiErrorResponse body = new ApiErrorResponse(
				HttpStatus.NOT_IMPLEMENTED.value(),
				"NOT_IMPLEMENTED",
				exception.getMessage() == null ? "Not implemented" : exception.getMessage());
		return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(body);
	}
}
