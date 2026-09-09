package yangsirly.rag_agent.rag;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** document_chunks 的持久化与 Retrieval 查询入口。 */
@Mapper
public interface DocumentChunkMapper extends BaseMapper<DocumentChunkEntity> {

    /**
     * 只返回指定知识库中“当前仍有效、且由当前 embedding 模型生成”的 chunk。
     *
     * <p>旧 chunk 可以继续留在表里等待延迟回收，但不会进入 Retrieval。</p>
     */
    @Select("""
        SELECT
            c.id,
            c.knowledge_base_id,
            c.document_id,
            c.chunk_index,
            c.content,
            c.start_offset,
            c.end_offset,
            c.embedding,
            c.embedding_model,
            c.embedding_dimension,
            c.source_document_updated_at,
            c.created_at,
            c.updated_at
        FROM document_chunks c
        JOIN documents d
          ON d.id = c.document_id
         AND d.knowledge_base_id = c.knowledge_base_id
        WHERE c.knowledge_base_id = #{knowledgeBaseId}

          -- 文档软删除后，chunk 即使物理存在也立即失去检索资格。
          AND d.deleted_at IS NULL

          -- 只读取由 Document 当前版本生成的 chunk。
          AND c.source_document_updated_at = d.updated_at

          -- Query 与 Chunk 必须来自同一个 embedding 模型/向量空间。
          AND c.embedding_model = #{embeddingModel}
          AND c.embedding IS NOT NULL

        ORDER BY c.document_id ASC, c.chunk_index ASC
        """)
    List<DocumentChunkEntity> listCurrentEmbeddedChunksByKnowledgeBaseId(
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("embeddingModel") String embeddingModel);
}
