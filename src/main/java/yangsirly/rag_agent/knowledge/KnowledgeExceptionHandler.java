package yangsirly.rag_agent.knowledge;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import yangsirly.rag_agent.registration.ApiErrorResponse;

/**
 * 知识库模块统一异常映射：将业务校验异常映射为 400 Bad Request。
 */
@RestControllerAdvice(assignableTypes = { KnowledgeBaseController.class, DocumentController.class })
public class KnowledgeExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        ApiErrorResponse body = new ApiErrorResponse(
                HttpStatus.BAD_REQUEST.value(),
                "INVALID_REQUEST",
                ex.getMessage());
        return ResponseEntity.badRequest().body(body);
    }
}
