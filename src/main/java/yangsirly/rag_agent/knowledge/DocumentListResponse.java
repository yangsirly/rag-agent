package yangsirly.rag_agent.knowledge;

import java.util.List;

/**
 * 文档列表分页响应 DTO（列表项剥离大正文，仅包含摘要与字数等元数据）。
 */
public record DocumentListResponse(
        int statusCode,
        List<DocumentListItemDto> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public record DocumentListItemDto(
            String id,
            String knowledgeBaseId,
            String creatorId,
            String title,
            String summary,
            Integer contentLength,
            String createdAt,
            String updatedAt) {
    }
}
