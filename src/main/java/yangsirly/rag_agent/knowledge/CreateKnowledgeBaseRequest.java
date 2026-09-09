package yangsirly.rag_agent.knowledge;

/**
 * 创建知识库请求入参 DTO。
 *
 * @param name 知识库名称
 * @param description 知识库描述
 */
public record CreateKnowledgeBaseRequest(
        String name,
        String description) {
}
