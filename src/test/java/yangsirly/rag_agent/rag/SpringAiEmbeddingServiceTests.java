package yangsirly.rag_agent.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

class SpringAiEmbeddingServiceTests {

    @Test
    void preservesBatchOrderAndModelName() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(List.of("A", "B")))
                .thenReturn(List.of(
                        new float[]{1.0f, 0.0f},
                        new float[]{0.0f, 1.0f}));

        SpringAiEmbeddingService service = new SpringAiEmbeddingService(
                model,
                "qwen3-embedding:0.6b");

        List<float[]> vectors = service.embedAll(List.of("A", "B"));

        // 第 i 个输入必须仍然对应第 i 个输出；否则 chunk 和 vector 会绑定错位。
        assertThat(vectors.get(0)).containsExactly(1.0f, 0.0f);
        assertThat(vectors.get(1)).containsExactly(0.0f, 1.0f);
        assertThat(service.modelName()).isEqualTo("qwen3-embedding:0.6b");
    }

    @Test
    void rejectsProviderCountMismatch() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(List.of("A", "B")))
                .thenReturn(List.of(new float[]{1.0f}));

        SpringAiEmbeddingService service = new SpringAiEmbeddingService(
                model,
                "qwen3-embedding:0.6b");

        assertThatThrownBy(() -> service.embedAll(List.of("A", "B")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("embedding 返回数量与输入数量不一致");
    }
}
