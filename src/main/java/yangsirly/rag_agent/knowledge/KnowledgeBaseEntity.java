package yangsirly.rag_agent.knowledge;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * knowledge_bases 表的 MyBatis-Plus 实体映射。
 */
@TableName("knowledge_bases")
public class KnowledgeBaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("creator_id")
    private Long creatorId;

    private String name;

    private String description;

    @TableField("editor_ids")
    private String editorIds;

    @TableField("reader_ids")
    private String readerIds;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    @TableField("deleted_at")
    private LocalDateTime deletedAt;

    /** MyBatis 映射所需的无参构造器 */
    protected KnowledgeBaseEntity() {
    }

    public KnowledgeBaseEntity(Long creatorId, String name, String description, LocalDateTime now) {
        this.creatorId = creatorId;
        this.name = name;
        this.description = description;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getCreatorId() {
        return creatorId;
    }

    public void setCreatorId(Long creatorId) {
        this.creatorId = creatorId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getEditorIds() {
        return editorIds;
    }

    public void setEditorIds(String editorIds) {
        this.editorIds = editorIds;
    }

    public String getReaderIds() {
        return readerIds;
    }

    public void setReaderIds(String readerIds) {
        this.readerIds = readerIds;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }
}
