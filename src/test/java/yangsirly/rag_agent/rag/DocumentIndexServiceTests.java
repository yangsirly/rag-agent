package yangsirly.rag_agent.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import yangsirly.rag_agent.knowledge.DocumentEntity;

class DocumentIndexServiceTests {

    @Test
    void buildsWholeVersionWithEmbeddingMetadata() {
        EmbeddingService embeddingService = mock(EmbeddingService.class);
        DocumentChunkVersionWriter writer = mock(DocumentChunkVersionWriter.class);
        VectorJsonCodec codec = new VectorJsonCodec(new ObjectMapper());

        // 这篇短文在 500 code points baseline 下只会产生一个 chunk。
        when(embeddingService.embedAll(anyList()))
                .thenReturn(List.of(new float[]{1.0f, 2.0f, 3.0f}));
        when(embeddingService.modelName()).thenReturn("qwen3-embedding:0.6b");

        DocumentIndexService service = new DocumentIndexService(
                new ParagraphChunker(),
                embeddingService,
                codec,
                writer);

        LocalDateTime version = LocalDateTime.of(2026, 9, 9, 16, 0);
        DocumentEntity document = new DocumentEntity(
                10L,
                1L,
                "报销制度",
                null,
                "报销单应在费用发生后30天内提交。",
                17,
                version);
        document.setId(100L);

        service.buildCurrentVersion(document);

        // 先验证真正送进 Embedding 的就是 chunk 文本。
        verify(embeddingService).embedAll(List.of("报销单应在费用发生后30天内提交。"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DocumentChunkEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).insertVersion(captor.capture());

        List<DocumentChunkEntity> rows = captor.getValue();
        assertThat(rows).hasSize(1);

        DocumentChunkEntity row = rows.getFirst();
        assertThat(row.getKnowledgeBaseId()).isEqualTo(10L);
        assertThat(row.getDocumentId()).isEqualTo(100L);
        assertThat(row.getSourceDocumentUpdatedAt()).isEqualTo(version);

        // 模型名 + 实际向量维度必须跟索引一起保存。
        assertThat(row.getEmbeddingModel()).isEqualTo("qwen3-embedding:0.6b");
        assertThat(row.getEmbeddingDimension()).isEqualTo(3);
        assertThat(codec.decode(row.getEmbedding())).containsExactly(1.0f, 2.0f, 3.0f);
    }
}
