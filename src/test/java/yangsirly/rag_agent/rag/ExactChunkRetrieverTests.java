package yangsirly.rag_agent.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class ExactChunkRetrieverTests {

    @Test
    void ranksMostSimilarChunkFirst() {
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        DocumentChunkMapper mapper = mock(DocumentChunkMapper.class);
        VectorJsonCodec codec = new VectorJsonCodec(new ObjectMapper());

        // Query 向量朝 x 轴方向。
        when(embeddingService.embed("报销最迟多久提交？"))
                .thenReturn(new float[]{1.0f, 0.0f});
        when(embeddingService.modelName()).thenReturn("qwen3-embedding:0.6b");

        DocumentChunkEntity relevant = chunk(
                1L, 101L, 0,
                "报销单应在费用发生后30天内提交。",
                codec.encode(new float[]{0.9f, 0.1f}));

        DocumentChunkEntity irrelevant = chunk(
                2L, 102L, 0,
                "办公用品采购需要主管审批。",
                codec.encode(new float[]{0.0f, 1.0f}));

        when(mapper.listCurrentEmbeddedChunksByKnowledgeBaseId(
                10L,
                "qwen3-embedding:0.6b"))
                .thenReturn(List.of(irrelevant, relevant));

        ExactChunkRetriever retriever = new ExactChunkRetriever(
                embeddingService,
                codec,
                mapper);

        List<RetrievedChunk> result = retriever.search(
                10L,
                "报销最迟多久提交？",
                2);

        // 输入顺序故意把 irrelevant 放前面；结果必须由 cosine 分数重新排序。
        assertThat(result).extracting(RetrievedChunk::documentId)
                .containsExactly(101L, 102L);
        assertThat(result.get(0).score()).isGreaterThan(result.get(1).score());
    }

    @Test
    void rejectsZeroVectorBecauseCosineHasNoDirection() {
        assertThatThrownBy(() -> ExactChunkRetriever.cosineSimilarity(
                new float[]{0.0f, 0.0f},
                new float[]{1.0f, 0.0f}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("embedding 不能是零向量");
    }

    private static DocumentChunkEntity chunk(
            Long id,
            Long documentId,
            int chunkIndex,
            String content,
            String embedding) {

        DocumentChunkEntity entity = new DocumentChunkEntity(
                10L,
                documentId,
                chunkIndex,
                content,
                0,
                content.codePointCount(0, content.length()),
                LocalDateTime.of(2026, 9, 9, 16, 0));
        entity.setId(id);
        entity.setEmbedding(embedding);
        entity.setEmbeddingModel("qwen3-embedding:0.6b");
        entity.setEmbeddingDimension(2);
        return entity;
    }
}
