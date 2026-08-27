package yangsirly.rag_agent.chat;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import yangsirly.rag_agent.common.exception.InvalidConversationRequestException;
import yangsirly.rag_agent.common.exception.InvalidMessageRequestException;
import yangsirly.rag_agent.common.exception.RateLimitExceededException;
import yangsirly.rag_agent.registration.ApiErrorResponse;

/**
 * 聊天域异常映射：统一转换为前端约定的 JSON 错误结构。
 */
@RestControllerAdvice(assignableTypes = {
        ConversationController.class,
        MessageController.class
})
public class ChatExceptionHandler {

    /** 请求体字段校验失败。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        ApiErrorResponse body = new ApiErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "INVALID_MESSAGE_REQUEST",
                "Message request fields are invalid");
        return ResponseEntity.badRequest().body(body);
    }

    /** 会话参数非法（如标题长度、分页参数越界）。 */
    @ExceptionHandler(InvalidConversationRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidConversation(InvalidConversationRequestException ex) {
        ApiErrorResponse body = new ApiErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "INVALID_CONVERSATION_REQUEST",
                ex.getMessage());
        return ResponseEntity.badRequest().body(body);
    }

    /** 消息参数非法（如内容为空、幂等键非法）。 */
    @ExceptionHandler(InvalidMessageRequestException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidMessage(InvalidMessageRequestException ex) {
        ApiErrorResponse body = new ApiErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "INVALID_MESSAGE_REQUEST",
                ex.getMessage());
        return ResponseEntity.badRequest().body(body);
    }

    /** 命中发送限流：返回 429 并附带限流相关响应头。 */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleRateLimit(RateLimitExceededException ex) {
        ApiErrorResponse body = new ApiErrorResponse(
                429,
                "RATE_LIMITED",
                ex.getMessage());
        return ResponseEntity.status(429)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .header("X-RateLimit-Limit", ex.getLimit() > 0 ? String.valueOf(ex.getLimit()) : "60")
                .body(body);
    }

    /**
     * 兜底处理路径参数等基础非法输入。
     *
     * <p>
     * 根据异常消息粗分错误码，保证前端可按码处理。
     * </p>
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        String code;
        String clientMessage;
        if (message.contains("path parameter") || message.contains("Path parameter")) {
            code = "INVALID_PATH_PARAMETER";
            clientMessage = "Path id must be a decimal long";
        } else if (message.toLowerCase().contains("title")
                || message.toLowerCase().contains("conversation")) {
            code = "INVALID_CONVERSATION_REQUEST";
            clientMessage = "Conversation request is invalid";
        } else {
            code = "INVALID_MESSAGE_REQUEST";
            clientMessage = "Message request is invalid";
        }
        ApiErrorResponse body = new ApiErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                code,
                clientMessage);
        return ResponseEntity.badRequest().body(body);
    }

    /** 会话不存在或不属于当前用户。 */
    @ExceptionHandler(ConversationNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(ConversationNotFoundException exception) {
        ApiErrorResponse body = new ApiErrorResponse(
                HttpStatus.NOT_FOUND.value(),
                "NOT_FOUND",
                exception.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /** 幂等键冲突：同一键对应不同内容。 */
    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiErrorResponse> handleIdempotencyConflict(IdempotencyConflictException exception) {
        ApiErrorResponse body = new ApiErrorResponse(
                HttpStatus.CONFLICT.value(),
                "IDEMPOTENCY_CONFLICT",
                exception.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /** 保留的未实现能力（例如后续模型能力扩展）。 */
    @ExceptionHandler(UnsupportedOperationException.class)
    public ResponseEntity<ApiErrorResponse> handleNotImplemented(UnsupportedOperationException exception) {
        ApiErrorResponse body = new ApiErrorResponse(
                HttpStatus.NOT_IMPLEMENTED.value(),
                "NOT_IMPLEMENTED",
                exception.getMessage() == null ? "Not implemented" : exception.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(body);
    }
}
