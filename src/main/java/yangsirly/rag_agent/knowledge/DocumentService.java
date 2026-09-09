package yangsirly.rag_agent.knowledge;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import yangsirly.rag_agent.registration.UserEntity;
import yangsirly.rag_agent.registration.UserMapper;

/** 文档业务逻辑服务。 */
@Service
public class DocumentService {

    private static final int MIN_TITLE_LENGTH = 1;
    private static final int MAX_TITLE_LENGTH = 100;
    private static final int MAX_SUMMARY_LENGTH = 500;
    private static final int MIN_CONTENT_LENGTH = 1;
    private static final int MAX_CONTENT_LENGTH = 50000;

    private static final int MAX_PAGE_INDEX = 100;
    private static final int MAX_PAGE_SIZE = 100;

    private final DocumentMapper documentMapper;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final UserMapper userMapper;
    private final Clock clock;

    public DocumentService(
            DocumentMapper documentMapper,
            KnowledgeBaseMapper knowledgeBaseMapper,
            UserMapper userMapper,
            Clock clock) {
        this.documentMapper = documentMapper;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.userMapper = userMapper;
        this.clock = clock;
    }

    /**
     * 创建业务 Document。RAG 索引由独立索引用例显式构建，
     * 避免把 Ollama HTTP 推理包进数据库事务。
     */
    public DocumentEntity create(Long creatorId, Long knowledgeBaseId, CreateDocumentRequest request) {
        validateUserExists(creatorId);
        validateKnowledgeBaseOwned(knowledgeBaseId, creatorId);

        if (request == null || request.title() == null || request.title().isBlank()) {
            throw new IllegalArgumentException("请求参数非法");
        }
        if (request.content() == null || request.content().isBlank()) {
            throw new IllegalArgumentException("请求参数非法");
        }

        String strippedTitle = request.title().strip();
        int titleLength = strippedTitle.codePointCount(0, strippedTitle.length());
        if (titleLength < MIN_TITLE_LENGTH || titleLength > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("请求参数非法");
        }

        String strippedContent = request.content().strip();
        int contentLength = strippedContent.codePointCount(0, strippedContent.length());
        if (contentLength < MIN_CONTENT_LENGTH || contentLength > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("请求参数非法");
        }

        String strippedSummary = null;
        if (request.summary() != null && !request.summary().isBlank()) {
            strippedSummary = request.summary().strip();
            int summaryLength = strippedSummary.codePointCount(0, strippedSummary.length());
            if (summaryLength > MAX_SUMMARY_LENGTH) {
                throw new IllegalArgumentException("请求参数非法");
            }
        }

        LocalDateTime now = LocalDateTime.now(clock);
        DocumentEntity entity = new DocumentEntity(
                knowledgeBaseId,
                creatorId,
                strippedTitle,
                strippedSummary,
                strippedContent,
                contentLength,
                now);

        // insert 后 MyBatis-Plus 会把 AUTO_INCREMENT id 回填到 entity。
        documentMapper.insert(entity);

        return entity;
    }

    /** 查询指定知识库下的单篇文档详情。 */
    public DocumentEntity getDetail(Long creatorId, Long knowledgeBaseId, Long documentId) {
        validateUserExists(creatorId);
        validateKnowledgeBaseOwned(knowledgeBaseId, creatorId);

        if (documentId == null) {
            throw new IllegalArgumentException("请求参数非法");
        }

        DocumentEntity entity = documentMapper.findByIdAndKnowledgeBaseId(documentId, knowledgeBaseId);
        if (entity == null) {
            throw new IllegalArgumentException("请求参数非法");
        }
        return entity;
    }

    /** 分页查询指定知识库下的有效文档列表。 */
    public DocumentPage list(Long creatorId, Long knowledgeBaseId, int page, int size) {
        validateUserExists(creatorId);
        validateKnowledgeBaseOwned(knowledgeBaseId, creatorId);

        if (page < 0 || page >= MAX_PAGE_INDEX) {
            throw new IllegalArgumentException("请求参数非法");
        }
        if (size <= 0 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("请求参数非法");
        }

        int offset = page * size;
        List<DocumentEntity> items = documentMapper.listByKnowledgeBaseId(knowledgeBaseId, offset, size);
        long totalElements = documentMapper.countByKnowledgeBaseId(knowledgeBaseId);
        int totalPages = size == 0 ? 0 : (int) Math.ceil((double) totalElements / size);

        return new DocumentPage(items, page, size, totalElements, totalPages);
    }

    /**
     * 更新业务 Document，并通过 updated_at 立即使旧 chunk 版本失效。
     * 新版本索引由独立索引用例随后构建；在此之前该文档宁可暂时不可检索，也不回退旧知识。
     */
    public DocumentEntity update(Long creatorId, Long knowledgeBaseId, Long documentId, UpdateDocumentRequest request) {
        validateUserExists(creatorId);
        validateKnowledgeBaseOwned(knowledgeBaseId, creatorId);

        if (documentId == null || request == null) {
            throw new IllegalArgumentException("请求参数非法");
        }
        if (request.title() == null && request.summary() == null && request.content() == null) {
            throw new IllegalArgumentException("请求参数非法");
        }

        DocumentEntity entity = documentMapper.findByIdAndKnowledgeBaseId(documentId, knowledgeBaseId);
        if (entity == null) {
            throw new IllegalArgumentException("请求参数非法");
        }

        boolean changed = false;

        if (request.title() != null) {
            String strippedTitle = request.title().strip();
            int titleLength = strippedTitle.codePointCount(0, strippedTitle.length());
            if (titleLength < MIN_TITLE_LENGTH || titleLength > MAX_TITLE_LENGTH) {
                throw new IllegalArgumentException("请求参数非法");
            }
            if (!strippedTitle.equals(entity.getTitle())) {
                entity.setTitle(strippedTitle);
                changed = true;
            }
        }

        if (request.summary() != null) {
            String strippedSummary = request.summary().strip();
            String newSummary = strippedSummary.isEmpty() ? null : strippedSummary;
            if (newSummary != null) {
                int summaryLength = newSummary.codePointCount(0, newSummary.length());
                if (summaryLength > MAX_SUMMARY_LENGTH) {
                    throw new IllegalArgumentException("请求参数非法");
                }
            }
            if (!java.util.Objects.equals(newSummary, entity.getSummary())) {
                entity.setSummary(newSummary);
                changed = true;
            }
        }

        if (request.content() != null) {
            String strippedContent = request.content().strip();
            int contentLength = strippedContent.codePointCount(0, strippedContent.length());
            if (contentLength < MIN_CONTENT_LENGTH || contentLength > MAX_CONTENT_LENGTH) {
                throw new IllegalArgumentException("请求参数非法");
            }
            if (!strippedContent.equals(entity.getContent())) {
                entity.setContent(strippedContent);
                entity.setContentLength(contentLength);
                changed = true;
            }
        }

        if (changed) {
            // updated_at 就是当前 P2.1 的“文档索引版本号”。
            entity.setUpdatedAt(LocalDateTime.now(clock));
            documentMapper.updateById(entity);
        }

        return entity;
    }

    /**
     * 软删除 Document 即可让其所有 chunk 立即失去检索资格。
     * 不在用户请求链路里同步物理删除 chunk；历史数据后续批量回收。
     */
    public void delete(Long creatorId, Long knowledgeBaseId, Long documentId) {
        validateUserExists(creatorId);
        validateKnowledgeBaseOwned(knowledgeBaseId, creatorId);

        if (documentId == null) {
            throw new IllegalArgumentException("请求参数非法");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        int affected = documentMapper.softDeleteById(documentId, now);
        if (affected == 0) {
            throw new IllegalArgumentException("请求参数非法");
        }
    }

    private void validateUserExists(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("请求参数非法");
        }
        UserEntity user = userMapper.selectById(userId);
        if (user == null) {
            throw new IllegalArgumentException("请求参数非法");
        }
    }

    private void validateKnowledgeBaseOwned(Long knowledgeBaseId, Long creatorId) {
        if (knowledgeBaseId == null) {
            throw new IllegalArgumentException("请求参数非法");
        }
        KnowledgeBaseEntity kb = knowledgeBaseMapper.findByIdAndCreatorId(knowledgeBaseId, creatorId);
        if (kb == null) {
            throw new IllegalArgumentException("请求参数非法");
        }
    }

    public record DocumentPage(
            List<DocumentEntity> items,
            int page,
            int size,
            long totalElements,
            int totalPages) {
    }
}
