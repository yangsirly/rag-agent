package yangsirly.rag_agent.knowledge;

/**
 * 单篇文档详情响应 DTO（包含完整正文内容）。
 */
public record DocumentResponse(
        int statusCode,
        String id,
        String knowledgeBaseId,
        String creatorId,
        String title,
        String summary,
        String content,
        Integer contentLength,
        String createdAt,
        String updatedAt) {
}
