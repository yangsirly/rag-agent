package yangsirly.rag_agent.rag;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * document_chunks 表的持久化实体。
 *
 * <p>一个 Document 可以同时保留多个历史版本的 chunk；真正是否可检索，
 * 由 Retriever 查询时比较 source_document_updated_at 与 documents.updated_at 决定。</p>
 */
@TableName("document_chunks")
public class DocumentChunkEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("knowledge_base_id")
    private Long knowledgeBaseId;

    @TableField("document_id")
    private Long documentId;

    @TableField("chunk_index")
    private Integer chunkIndex;

    private String content;

    @TableField("start_offset")
    private Integer startOffset;

    @TableField("end_offset")
    private Integer endOffset;

    /**
     * P2.1 方案 B 暂时直接保存 JSON 数组字符串，例如 "[0.1,-0.2,0.3]"。
     * Embedding 模型尚未选定前允许为空。
     */
    private String embedding;

    @TableField("embedding_model")
    private String embeddingModel;

    @TableField("embedding_dimension")
    private Integer embeddingDimension;

    /** 生成该 chunk 时，源文档的 updated_at。 */
    @TableField("source_document_updated_at")
    private LocalDateTime sourceDocumentUpdatedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    protected DocumentChunkEntity() {
    }

    public DocumentChunkEntity(
            Long knowledgeBaseId,
            Long documentId,
            Integer chunkIndex,
            String content,
            Integer startOffset,
            Integer endOffset,
            LocalDateTime sourceDocumentUpdatedAt) {
        this.knowledgeBaseId = knowledgeBaseId;
        this.documentId = documentId;
        this.chunkIndex = chunkIndex;
        this.content = content;
        this.startOffset = startOffset;
        this.endOffset = endOffset;
        this.sourceDocumentUpdatedAt = sourceDocumentUpdatedAt;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getKnowledgeBaseId() { return knowledgeBaseId; }
    public Long getDocumentId() { return documentId; }
    public Integer getChunkIndex() { return chunkIndex; }
    public String getContent() { return content; }
    public Integer getStartOffset() { return startOffset; }
    public Integer getEndOffset() { return endOffset; }
    public String getEmbedding() { return embedding; }
    public void setEmbedding(String embedding) { this.embedding = embedding; }
    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }
    public Integer getEmbeddingDimension() { return embeddingDimension; }
    public void setEmbeddingDimension(Integer embeddingDimension) { this.embeddingDimension = embeddingDimension; }
    public LocalDateTime getSourceDocumentUpdatedAt() { return sourceDocumentUpdatedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
