package yangsirly.rag_agent.knowledge;

/**
 * 修改知识库请求入参 DTO。
 *
 * @param name 知识库新名称（选填）
 * @param description 知识库新描述（选填）
 */
public record UpdateKnowledgeBaseRequest(
        String name,
        String description) {
}
