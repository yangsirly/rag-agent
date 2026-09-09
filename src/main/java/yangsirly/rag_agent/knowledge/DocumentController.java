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
import yangsirly.rag_agent.knowledge.DocumentListResponse.DocumentListItemDto;

/**
 * 文档模块 HTTP 控制器。
 */
@RestController
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * 在指定知识库下创建文档。
     */
    @PostMapping("/knowledge-bases/{knowledgeBaseId}/documents")
    public ResponseEntity<DocumentResponse> create(
            @PathVariable String knowledgeBaseId,
            @RequestBody(required = false) CreateDocumentRequest request,
            Authentication authentication) {
        AuthenticatedUser user = requireUser(authentication);
        Long parsedKbId = parseId(knowledgeBaseId);
        DocumentEntity entity = documentService.create(user.userId(), parsedKbId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(201, entity));
    }

    /**
     * 查看单篇文档详情（包含完整大文本）。
     */
    @GetMapping("/knowledge-bases/{knowledgeBaseId}/documents/{documentId}")
    public ResponseEntity<DocumentResponse> getDetail(
            @PathVariable String knowledgeBaseId,
            @PathVariable String documentId,
            Authentication authentication) {
        AuthenticatedUser user = requireUser(authentication);
        Long parsedKbId = parseId(knowledgeBaseId);
        Long parsedDocId = parseId(documentId);
        DocumentEntity entity = documentService.getDetail(user.userId(), parsedKbId, parsedDocId);
        return ResponseEntity.ok(toResponse(200, entity));
    }

    /**
     * 分页查询某知识库下的文档列表（按需剥离正文，仅返回元数据）。
     */
    @GetMapping("/knowledge-bases/{knowledgeBaseId}/documents")
    public ResponseEntity<DocumentListResponse> list(
            @PathVariable String knowledgeBaseId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        AuthenticatedUser user = requireUser(authentication);
        Long parsedKbId = parseId(knowledgeBaseId);
        DocumentService.DocumentPage p = documentService.list(user.userId(), parsedKbId, page, size);

        List<DocumentListItemDto> items = p.items().stream()
                .map(this::toListItemDto)
                .toList();

        DocumentListResponse resp = new DocumentListResponse(
                200, items, p.page(), p.size(), p.totalElements(), p.totalPages());
        return ResponseEntity.ok(resp);
    }

    /**
     * 修改文档。
     */
    @PatchMapping("/knowledge-bases/{knowledgeBaseId}/documents/{documentId}")
    public ResponseEntity<DocumentResponse> update(
            @PathVariable String knowledgeBaseId,
            @PathVariable String documentId,
            @RequestBody(required = false) UpdateDocumentRequest request,
            Authentication authentication) {
        AuthenticatedUser user = requireUser(authentication);
        Long parsedKbId = parseId(knowledgeBaseId);
        Long parsedDocId = parseId(documentId);
        DocumentEntity entity = documentService.update(user.userId(), parsedKbId, parsedDocId, request);
        return ResponseEntity.ok(toResponse(200, entity));
    }

    /**
     * 软删除文档。
     */
    @DeleteMapping("/knowledge-bases/{knowledgeBaseId}/documents/{documentId}")
    public ResponseEntity<Void> delete(
            @PathVariable String knowledgeBaseId,
            @PathVariable String documentId,
            Authentication authentication) {
        AuthenticatedUser user = requireUser(authentication);
        Long parsedKbId = parseId(knowledgeBaseId);
        Long parsedDocId = parseId(documentId);
        documentService.delete(user.userId(), parsedKbId, parsedDocId);
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

    private DocumentResponse toResponse(int statusCode, DocumentEntity entity) {
        return new DocumentResponse(
                statusCode,
                entity.getId() == null ? null : entity.getId().toString(),
                entity.getKnowledgeBaseId() == null ? null : entity.getKnowledgeBaseId().toString(),
                entity.getCreatorId() == null ? null : entity.getCreatorId().toString(),
                entity.getTitle(),
                entity.getSummary(),
                entity.getContent(),
                entity.getContentLength(),
                formatUtc(entity.getCreatedAt()),
                formatUtc(entity.getUpdatedAt()));
    }

    private DocumentListItemDto toListItemDto(DocumentEntity entity) {
        return new DocumentListItemDto(
                entity.getId() == null ? null : entity.getId().toString(),
                entity.getKnowledgeBaseId() == null ? null : entity.getKnowledgeBaseId().toString(),
                entity.getCreatorId() == null ? null : entity.getCreatorId().toString(),
                entity.getTitle(),
                entity.getSummary(),
                entity.getContentLength(),
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
