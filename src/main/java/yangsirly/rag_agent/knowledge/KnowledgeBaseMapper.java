package yangsirly.rag_agent.knowledge;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

/**
 * knowledge_bases 表的持久化 Mapper 接口。
 */
@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBaseEntity> {

    /** 按主键与创建者 ID 查询有效知识库；不存在、非本人或已删除返回 null。 */
    default KnowledgeBaseEntity findByIdAndCreatorId(Long id, Long creatorId) {
        return selectOne(Wrappers.<KnowledgeBaseEntity>lambdaQuery()
                .eq(KnowledgeBaseEntity::getId, id)
                .eq(KnowledgeBaseEntity::getCreatorId, creatorId)
                .isNull(KnowledgeBaseEntity::getDeletedAt));
    }

    /** 检查同创建者下是否已存在有效同名知识库。 */
    default KnowledgeBaseEntity findActiveByNameAndCreatorId(String name, Long creatorId) {
        return selectOne(Wrappers.<KnowledgeBaseEntity>lambdaQuery()
                .eq(KnowledgeBaseEntity::getCreatorId, creatorId)
                .eq(KnowledgeBaseEntity::getName, name)
                .isNull(KnowledgeBaseEntity::getDeletedAt));
    }

    /** 分页获取当前创建者的有效知识库列表，按 updatedAt DESC, id DESC 排序。 */
    default List<KnowledgeBaseEntity> listByCreatorId(Long creatorId, int offset, int size) {
        return selectList(Wrappers.<KnowledgeBaseEntity>lambdaQuery()
                .eq(KnowledgeBaseEntity::getCreatorId, creatorId)
                .isNull(KnowledgeBaseEntity::getDeletedAt)
                .orderByDesc(KnowledgeBaseEntity::getUpdatedAt)
                .orderByDesc(KnowledgeBaseEntity::getId)
                .last("LIMIT " + offset + ", " + size));
    }

    /** 获取当前创建者的有效知识库总数。 */
    default long countByCreatorId(Long creatorId) {
        return selectCount(Wrappers.<KnowledgeBaseEntity>lambdaQuery()
                .eq(KnowledgeBaseEntity::getCreatorId, creatorId)
                .isNull(KnowledgeBaseEntity::getDeletedAt));
    }

    /** 软删除知识库：更新 deleted_at 时间戳。 */
    @Update("UPDATE knowledge_bases SET deleted_at = #{now} WHERE id = #{id} AND deleted_at IS NULL")
    int softDeleteById(@Param("id") Long id, @Param("now") LocalDateTime now);
}
