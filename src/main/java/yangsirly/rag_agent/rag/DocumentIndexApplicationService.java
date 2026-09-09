package yangsirly.rag_agent.rag;

import org.springframework.stereotype.Service;

import yangsirly.rag_agent.knowledge.DocumentEntity;
import yangsirly.rag_agent.knowledge.DocumentService;

/**
 * “索引某篇现有文档”的应用用例。
 *
 * <p>权限检查复用 DocumentService.getDetail：只有当前允许读取该文档的主体
 * 才能触发索引。真正的 Chunk/Embedding 细节仍留在 DocumentIndexService。</p>
 */
@Service
public class DocumentIndexApplicationService {

    private final DocumentService documentService;
    private final DocumentIndexService documentIndexService;

    public DocumentIndexApplicationService(
            DocumentService documentService,
            DocumentIndexService documentIndexService) {
        this.documentService = documentService;
        this.documentIndexService = documentIndexService;
    }

    public void indexDocument(Long userId, Long knowledgeBaseId, Long documentId) {
        // 1. 先走现有业务权限边界，拿到当前有效 Document。
        DocumentEntity document = documentService.getDetail(
                userId,
                knowledgeBaseId,
                documentId);

        // 2. 再在事务外调用本地 Ollama 生成当前版本索引。
        //    如果期间 Document 又被修改，这批 chunk 的 source_document_updated_at
        //    会变成旧版本，Retriever 会自动排除，不会污染当前知识。
        documentIndexService.buildCurrentVersion(document);
    }
}
