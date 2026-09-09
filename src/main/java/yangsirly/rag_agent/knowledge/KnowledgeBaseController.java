package yangsirly.rag_agent.knowledge;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

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

/**
 * 知识库模块 HTTP 控制器。
 */
@RestController
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeBaseController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    /**
     * 创建知识库。
     */
    @PostMapping("/knowledge-bases")
    public ResponseEntity<KnowledgeBaseResponse> create(
            @RequestBody(required = false) CreateKnowledgeBaseRequest request,
            Authentication authentication) {
        AuthenticatedUser user = requireUser(authentication);
        KnowledgeBaseEntity entity = knowledgeBaseService.create(user.userId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(201, entity));
    }

    /**
     * 查看知识库详情。
     */
    @GetMapping("/knowledge-bases/{id}")
    public ResponseEntity<KnowledgeBaseResponse> getDetail(
            @PathVariable String id,
            Authentication authentication) {
        AuthenticatedUser user = requireUser(authentication);
        Long parsedId = parseId(id);
        KnowledgeBaseEntity entity = knowledgeBaseService.getDetail(user.userId(), parsedId);
        return ResponseEntity.ok(toResponse(200, entity));
    }

    /**
     * 分页查询当前用户的知识库列表。
     */
    @GetMapping("/knowledge-bases")
    public ResponseEntity<KnowledgeBaseListResponse> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        AuthenticatedUser user = requireUser(authentication);
        KnowledgeBaseService.KnowledgeBasePage p = knowledgeBaseService.list(user.userId(), page, size);
        List<KnowledgeBaseResponse> items = p.items().stream()
                .map(e -> toResponse(200, e))
                .toList();

        KnowledgeBaseListResponse resp = new KnowledgeBaseListResponse(
                200, items, p.page(), p.size(), p.totalElements(), p.totalPages());
        return ResponseEntity.ok(resp);
    }

    /**
     * 修改知识库。
     */
    @PatchMapping("/knowledge-bases/{id}")
    public ResponseEntity<KnowledgeBaseResponse> update(
            @PathVariable String id,
            @RequestBody(required = false) UpdateKnowledgeBaseRequest request,
            Authentication authentication) {
        AuthenticatedUser user = requireUser(authentication);
        Long parsedId = parseId(id);
        KnowledgeBaseEntity entity = knowledgeBaseService.update(user.userId(), parsedId, request);
        return ResponseEntity.ok(toResponse(200, entity));
    }

    /**
     * 软删除知识库。
     */
    @DeleteMapping("/knowledge-bases/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable String id,
            Authentication authentication) {
        AuthenticatedUser user = requireUser(authentication);
        Long parsedId = parseId(id);
        knowledgeBaseService.delete(user.userId(), parsedId);
        return ResponseEntity.noContent().build();
    }

    private static AuthenticatedUser requireUser(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new IllegalStateException("Authenticated user principal is required");
        }
        return user;
    }

    private static Long parseId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("请求参数非法");
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("请求参数非法", ex);
        }
    }

    private static KnowledgeBaseResponse toResponse(int statusCode, KnowledgeBaseEntity entity) {
        return new KnowledgeBaseResponse(
                statusCode,
                entity.getId() == null ? null : entity.getId().toString(),
                entity.getCreatorId() == null ? null : entity.getCreatorId().toString(),
                entity.getName(),
                entity.getDescription(),
                entity.getEditorIds(),
                entity.getReaderIds(),
                formatUtc(entity.getCreatedAt()),
                formatUtc(entity.getUpdatedAt()));
    }

    private static String formatUtc(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return DateTimeFormatter.ISO_INSTANT.format(dateTime.toInstant(ZoneOffset.UTC));
    }
}
