package yangsirly.rag_agent.rag;

import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;

/**
 * P2.1 方案 B：在 Java 内存中做精确 cosine Top-K。
 *
 * <p>它故意不是高性能实现。当前目标是把“Query Embedding -> 相似度 -> 排序 -> Top-K”
 * 完整暴露出来，等数据规模真的成为瓶颈后再替换为 ANN / Vector DB。</p>
 */
@Service
public class ExactChunkRetriever {

    private final EmbeddingService embeddingService;
    private final VectorJsonCodec vectorJsonCodec;
    private final DocumentChunkMapper documentChunkMapper;

    public ExactChunkRetriever(
            EmbeddingService embeddingService,
            VectorJsonCodec vectorJsonCodec,
            DocumentChunkMapper documentChunkMapper) {
        this.embeddingService = embeddingService;
        this.vectorJsonCodec = vectorJsonCodec;
        this.documentChunkMapper = documentChunkMapper;
    }

    /**
     * 在指定知识库内找最相似的 Top-K chunks。
     */
    public List<RetrievedChunk> search(Long knowledgeBaseId, String query, int topK) {
        if (knowledgeBaseId == null) {
            throw new IllegalArgumentException("knowledgeBaseId 不能为空");
        }
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query 不能为空");
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK 必须大于 0");
        }

        // 1. Query 必须和 chunk 使用同一个 EmbeddingService / modelName。
        float[] queryVector = embeddingService.embed(query);

        // 2. SQL 已经先过滤：
        //    - 文档未软删除
        //    - chunk 属于 Document 当前 updated_at 版本
        //    - embedding_model 与当前模型一致
        List<DocumentChunkEntity> candidates =
                documentChunkMapper.listCurrentEmbeddedChunksByKnowledgeBaseId(
                        knowledgeBaseId,
                        embeddingService.modelName());

        // 3. 对当前 KB 的每一个候选做精确 cosine，相当于一次线性扫描。
        return candidates.stream()
                .map(chunk -> {
                    float[] chunkVector = vectorJsonCodec.decode(chunk.getEmbedding());

                    if (chunkVector.length != queryVector.length) {
                        // 同模型正常情况下维度应一致；出现不一致说明索引数据异常。
                        throw new IllegalStateException("query 与 chunk embedding 维度不一致");
                    }

                    double score = cosineSimilarity(queryVector, chunkVector);

                    return new RetrievedChunk(
                            chunk.getId(),
                            chunk.getDocumentId(),
                            chunk.getChunkIndex(),
                            chunk.getContent(),
                            score);
                })
                // cosine 越大越相似，所以按 score 降序。
                .sorted(Comparator.comparingDouble(RetrievedChunk::score).reversed())
                .limit(topK)
                .toList();
    }

    /**
     * cosine(A,B) = A·B / (|A| * |B|)
     *
     * <p>这里用 double 累加，减少长向量求和时的数值误差；
     * 输入仍然保持模型原始的 float[]，没有必要把存储体积扩大一倍。</p>
     */
    static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            throw new IllegalArgumentException("cosine 输入向量非法");
        }

        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }

        // 零向量没有方向，cosine 无定义；不能偷偷返回 0 混进排序。
        if (normA == 0.0 || normB == 0.0) {
            throw new IllegalStateException("embedding 不能是零向量");
        }

        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
