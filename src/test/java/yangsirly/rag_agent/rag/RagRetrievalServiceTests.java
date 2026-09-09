package yangsirly.rag_agent.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import yangsirly.rag_agent.knowledge.KnowledgeBaseService;

class RagRetrievalServiceTests {

    @Test
    void checksKnowledgeBaseAccessBeforeSearching() {
        KnowledgeBaseService knowledgeBaseService = mock(KnowledgeBaseService.class);
        ExactChunkRetriever retriever = mock(ExactChunkRetriever.class);

        List<RetrievedChunk> expected = List.of(
                new RetrievedChunk(1L, 101L, 0, "证据", 0.91));
        when(retriever.search(10L, "问题", 3)).thenReturn(expected);

        RagRetrievalService service = new RagRetrievalService(
                knowledgeBaseService,
                retriever);

        List<RetrievedChunk> result = service.search(1L, 10L, "问题", 3);

        // 先经过现有知识库权限边界，再允许底层 Retriever 读取该 KB。
        verify(knowledgeBaseService).getDetail(1L, 10L);
        verify(retriever).search(10L, "问题", 3);
        assertThat(result).isEqualTo(expected);
    }
}
