package yangsirly.rag_agent.knowledge;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

/**
 * documents 表的持久化 Mapper 接口。
 */
@Mapper
public interface DocumentMapper extends BaseMapper<DocumentEntity> {

    /** 查询某知识库下的指定有效文档。 */
    default DocumentEntity findByIdAndKnowledgeBaseId(Long id, Long knowledgeBaseId) {
        return selectOne(Wrappers.<DocumentEntity>lambdaQuery()
                .eq(DocumentEntity::getId, id)
                .eq(DocumentEntity::getKnowledgeBaseId, knowledgeBaseId)
                .isNull(DocumentEntity::getDeletedAt));
    }

    /** 检查某知识库下是否已存在同名有效文档。 */
    default DocumentEntity findActiveByTitleAndKnowledgeBaseId(String title, Long knowledgeBaseId) {
        return selectOne(Wrappers.<DocumentEntity>lambdaQuery()
                .eq(DocumentEntity::getKnowledgeBaseId, knowledgeBaseId)
                .eq(DocumentEntity::getTitle, title)
                .isNull(DocumentEntity::getDeletedAt));
    }

    /** 分页获取某知识库下的有效文档列表，按 updatedAt DESC, id DESC 排序。 */
    default List<DocumentEntity> listByKnowledgeBaseId(Long knowledgeBaseId, int offset, int size) {
        return selectList(Wrappers.<DocumentEntity>lambdaQuery()
                .eq(DocumentEntity::getKnowledgeBaseId, knowledgeBaseId)
                .isNull(DocumentEntity::getDeletedAt)
                .orderByDesc(DocumentEntity::getUpdatedAt)
                .orderByDesc(DocumentEntity::getId)
                .last("LIMIT " + offset + ", " + size));
    }

    /** 获取某知识库下的有效文档总数。 */
    default long countByKnowledgeBaseId(Long knowledgeBaseId) {
        return selectCount(Wrappers.<DocumentEntity>lambdaQuery()
                .eq(DocumentEntity::getKnowledgeBaseId, knowledgeBaseId)
                .isNull(DocumentEntity::getDeletedAt));
    }

    /** 软删除单篇文档。 */
    @Update("UPDATE documents SET deleted_at = #{now} WHERE id = #{id} AND deleted_at IS NULL")
    int softDeleteById(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** 级联软删除某知识库下的全部文档。 */
    @Update("UPDATE documents SET deleted_at = #{now} WHERE knowledge_base_id = #{knowledgeBaseId} AND deleted_at IS NULL")
    int softDeleteByKnowledgeBaseId(@Param("knowledgeBaseId") Long knowledgeBaseId, @Param("now") LocalDateTime now);
}
