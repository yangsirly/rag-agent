package yangsirly.rag_agent.knowledge;

/**
 * 知识库详情/单体响应 DTO。
 */
public record KnowledgeBaseResponse(
        int statusCode,
        String id,
        String creatorId,
        String name,
        String description,
        String editorIds,
        String readerIds,
        String createdAt,
        String updatedAt) {
}
