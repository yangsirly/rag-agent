package yangsirly.rag_agent.knowledge;

import java.util.List;

/**
 * 知识库列表分页响应 DTO。
 */
public record KnowledgeBaseListResponse(
        int statusCode,
        List<KnowledgeBaseResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
