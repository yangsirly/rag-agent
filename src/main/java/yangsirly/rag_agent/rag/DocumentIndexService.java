package yangsirly.rag_agent.rag;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import yangsirly.rag_agent.knowledge.DocumentEntity;

/**
 * 把业务 Document 的当前版本转换成 Retrieval 使用的派生索引数据。
 *
 * <p>执行顺序固定为：</p>
 * <pre>
 * Document.content
 *   -> ParagraphChunker
 *   -> EmbeddingService（事务外）
 *   -> DocumentChunkVersionWriter（短事务）
 * </pre>
 */
@Service
public class DocumentIndexService {

    /** baseline 参数；以后通过固定评测集比较不同 chunk size。 */
    private static final int BASELINE_MAX_CODE_POINTS = 500;

    private final ParagraphChunker paragraphChunker;
    private final EmbeddingService embeddingService;
    private final VectorJsonCodec vectorJsonCodec;
    private final DocumentChunkVersionWriter chunkVersionWriter;

    public DocumentIndexService(
            ParagraphChunker paragraphChunker,
            EmbeddingService embeddingService,
            VectorJsonCodec vectorJsonCodec,
            DocumentChunkVersionWriter chunkVersionWriter) {
        this.paragraphChunker = paragraphChunker;
        this.embeddingService = embeddingService;
        this.vectorJsonCodec = vectorJsonCodec;
        this.chunkVersionWriter = chunkVersionWriter;
    }

    /**
     * 为 Document 当前 updated_at 对应的版本建立完整索引。
     *
     * <p>历史 chunk 不删除。只要 Document.updated_at 改变，历史版本会因为
     * source_document_updated_at 不匹配而立刻退出 Retrieval。</p>
     */
    public void buildCurrentVersion(DocumentEntity document) {
        if (document == null
                || document.getId() == null
                || document.getKnowledgeBaseId() == null
                || document.getUpdatedAt() == null
                || document.getContent() == null
                || document.getContent().isBlank()) {
            throw new IllegalArgumentException("待索引文档状态非法");
        }

        // 1. 先切块：得到稳定的 chunkIndex / offset / content。
        List<TextChunk> chunks = paragraphChunker.split(
                document.getContent(),
                BASELINE_MAX_CODE_POINTS);

        // 2. 一次批量请求本地 Ollama。
        //    这一步可能较慢，所以此时还没有打开数据库事务。
        List<String> texts = chunks.stream()
                .map(TextChunk::content)
                .toList();
        List<float[]> vectors = embeddingService.embedAll(texts);

        if (vectors.size() != chunks.size()) {
            // EmbeddingService 已经检查过，这里再守住业务层的一一对应不变量。
            throw new IllegalStateException("chunk 与 embedding 数量不一致");
        }

        // 3. 在内存中先把“一整个版本”准备完整。
        List<DocumentChunkEntity> rows = new ArrayList<>(chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            TextChunk chunk = chunks.get(i);
            float[] vector = vectors.get(i);

            DocumentChunkEntity row = new DocumentChunkEntity(
                    document.getKnowledgeBaseId(),
                    document.getId(),
                    chunk.chunkIndex(),
                    chunk.content(),
                    chunk.startOffset(),
                    chunk.endOffset(),
                    document.getUpdatedAt());

            // MySQL baseline 使用 JSON 保存 float[]。
            row.setEmbedding(vectorJsonCodec.encode(vector));

            // 模型名和维度一起保存：换模型或数据异常时可以可靠识别。
            row.setEmbeddingModel(embeddingService.modelName());
            row.setEmbeddingDimension(vector.length);

            rows.add(row);
        }

        // 4. 到这里 Embedding 已全部成功，才开启短事务一次写入整个版本。
        //    任意 INSERT 失败都会回滚这一版，不留下“半套索引”。
        chunkVersionWriter.insertVersion(rows);
    }
}
