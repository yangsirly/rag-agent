package yangsirly.rag_agent.rag;

/**
 * Exact Retrieval 的只读结果。
 *
 * @param chunkId chunk 主键
 * @param documentId 来源文档
 * @param chunkIndex 文档内顺序
 * @param content chunk 正文
 * @param score cosine similarity，越大越相似
 */
public record RetrievedChunk(
        Long chunkId,
        Long documentId,
        Integer chunkIndex,
        String content,
        double score) {
}
