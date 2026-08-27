package yangsirly.rag_agent.chat;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import yangsirly.rag_agent.authentication.AuthenticatedUser;
import yangsirly.rag_agent.chat.ConversationListResponse.ConversationViewDto;

/**
 * 会话相关 HTTP 接口：创建、读取、列表、重命名、删除。
 */
@RestController
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    /** 创建会话；未传标题时由服务层回退默认标题。 */
    @PostMapping("/conversations")
    public ResponseEntity<ConversationResponse> create(
            @RequestBody(required = false) CreateConversationRequest request,
            Authentication authentication) {
        AuthenticatedUser user = requireUser(authentication);
        String title = request == null ? null : request.title();
        ConversationService.ConversationView view = conversationService
                .create(new CreateConversationCommand(user.userId(), title));
        return ResponseEntity.status(HttpStatus.CREATED).body(ConversationService.toResponse(201, view));
    }

    /** 读取当前用户拥有的单个会话。 */
    @GetMapping("/conversations/{conversationId}")
    public ResponseEntity<ConversationResponse> get(
            @PathVariable String conversationId,
            Authentication authentication) {
        AuthenticatedUser user = requireUser(authentication);
        Long id = parseId(conversationId);
        ConversationService.ConversationView view = conversationService.getOwned(user.userId(), id);
        return ResponseEntity.ok(ConversationService.toResponse(200, view));
    }

    /** 分页查询当前用户会话列表。 */
    @GetMapping("/conversations")
    public ResponseEntity<ConversationListResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        AuthenticatedUser user = requireUser(authentication);
        ConversationService.ConversationPage p = conversationService.list(user.userId(), page, size);
        var items = p.items().stream().map(v -> new ConversationViewDto(
                v.id().toString(),
                v.title(),
                ConversationService.formatUtc(v.createdAt()),
                ConversationService.formatUtc(v.updatedAt()))).toList();
        ConversationListResponse resp = new ConversationListResponse(200, items, p.page(), p.size(), p.totalElements(),
                p.totalPages());
        return ResponseEntity.ok(resp);
    }

    /** 重命名会话。 */
    @PatchMapping("/conversations/{conversationId}")
    public ResponseEntity<ConversationResponse> rename(
            @PathVariable String conversationId,
            @RequestBody RenameConversationRequest request,
            Authentication authentication) {
        AuthenticatedUser user = requireUser(authentication);
        Long id = parseId(conversationId);
        ConversationService.ConversationView view = conversationService.rename(user.userId(), id,
                request == null ? null : request.title());
        return ResponseEntity.ok(ConversationService.toResponse(200, view));
    }

    /** 删除会话（服务层执行分批软删 + 会话软删）。 */
    @DeleteMapping("/conversations/{conversationId}")
    public ResponseEntity<Void> delete(
            @PathVariable String conversationId,
            Authentication authentication) {
        AuthenticatedUser user = requireUser(authentication);
        Long id = parseId(conversationId);
        conversationService.delete(user.userId(), id);
        return ResponseEntity.noContent().build();
    }

    /** 提取并校验认证主体。 */
    private static AuthenticatedUser requireUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new IllegalStateException("Authenticated user principal is required");
        }
        return user;
    }

    /**
     * 解析路径 id。
     *
     * <p>
     * 统一抛 IllegalArgumentException，交由异常处理器映射为 400。
     * </p>
     */
    static Long parseId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Invalid path parameter");
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Invalid path parameter", ex);
        }
    }

    /** PATCH 请求体。 */
    public record RenameConversationRequest(String title) {
    }
}
