package yangsirly.rag_agent.rag;

import java.util.List;

import org.springframework.stereotype.Service;

import yangsirly.rag_agent.knowledge.KnowledgeBaseService;

/**
 * 对外的 Retrieval 应用边界。
 *
 * <p>ExactChunkRetriever 只负责算法，不负责鉴权；这里先通过现有
 * KnowledgeBaseService 校验当前用户是否有权访问该知识库，再执行检索。</p>
 */
@Service
public class RagRetrievalService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final ExactChunkRetriever exactChunkRetriever;

    public RagRetrievalService(
            KnowledgeBaseService knowledgeBaseService,
            ExactChunkRetriever exactChunkRetriever) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.exactChunkRetriever = exactChunkRetriever;
    }

    public List<RetrievedChunk> search(
            Long userId,
            Long knowledgeBaseId,
            String query,
            int topK) {

        // 1. 权限必须在可信服务边界先校验，不能把 knowledgeBaseId 直接交给底层 Retriever。
        knowledgeBaseService.getDetail(userId, knowledgeBaseId);

        // 2. 权限确认后，底层算法只处理“这个 KB 内如何做 Top-K”。
        return exactChunkRetriever.search(knowledgeBaseId, query, topK);
    }
}
