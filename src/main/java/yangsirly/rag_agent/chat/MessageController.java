package yangsirly.rag_agent.chat;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import yangsirly.rag_agent.authentication.AuthenticatedUser;
import yangsirly.rag_agent.common.exception.InvalidMessageRequestException;

/**
 * 消息接口入口：发送消息与分页查询历史。
 */
@RestController
public class MessageController {

    private final MessageService messageService;

    public MessageController(MessageService messageService) {
        this.messageService = messageService;
    }

    /**
     * 发送消息。
     *
     * <p>
     * 支持通过 Idempotency-Key 头覆盖请求体 clientMessageId；
     * 两者同时存在且不一致时直接拒绝。
     * </p>
     */
    @PostMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<SendMessageResponse> send(
            @PathVariable String conversationId,
            @Valid @RequestBody SendMessageRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {
        AuthenticatedUser user = requireUser(authentication);
        Long id = ConversationController.parseId(conversationId);

        String clientMessageId = request.clientMessageId();
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            if (clientMessageId != null && !idempotencyKey.equals(clientMessageId)) {
                throw new InvalidMessageRequestException("Idempotency-Key header does not match clientMessageId");
            }
            clientMessageId = idempotencyKey;
        }

        SendMessageCommand command = new SendMessageCommand(
                user.userId(),
                id,
                clientMessageId,
                request.content());

        MessageService.SendResult result = messageService.send(command);
        return ResponseEntity.status(result.httpStatus()).body(result.toResponse());
    }

    /** 查询消息历史，支持 offset 分页和游标分页。 */
    @GetMapping("/conversations/{conversationId}/messages")
    public ResponseEntity<MessageListResponse> list(
            @PathVariable String conversationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) Long cursor,
            Authentication authentication) {
        AuthenticatedUser user = requireUser(authentication);
        Long id = ConversationController.parseId(conversationId);

        MessageService.MessagePage messagePage = messageService.listMessages(user.userId(), id, page, size, cursor);
        return ResponseEntity.ok(messagePage.toResponse());
    }

    /** 提取并校验认证主体。 */
    private static AuthenticatedUser requireUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new IllegalStateException("Authenticated user principal is required");
        }
        return user;
    }
}
