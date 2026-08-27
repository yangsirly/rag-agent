package yangsirly.rag_agent.chat;

import java.time.LocalDateTime;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

/**
 * conversations 表的持久化边界。
 *
 * <p>工业级改造（V5）后所有查询都过滤 {@code deleted_at IS NULL}：
 * 会话软删除后对外表现为"不存在"，但行保留，messages 外键
 * （ON DELETE RESTRICT）始终保持完整，不再需要级联删除。</p>
 */
@Mapper
public interface ConversationMapper extends BaseMapper<ConversationEntity> {

    /** 按主键 + 归属用户查找有效会话；已软删除或非本人会话返回 null（对外等同不存在）。 */
    default ConversationEntity findByIdAndUserId(Long id, Long userId) {
        return selectOne(Wrappers.<ConversationEntity>lambdaQuery()
                .eq(ConversationEntity::getId, id)
                .eq(ConversationEntity::getUserId, userId)
                .isNull(ConversationEntity::getDeletedAt));
    }

    /** 当前用户的有效会话列表，按 updatedAt DESC, id DESC 取一页。 */
    default java.util.List<ConversationEntity> listByUserId(Long userId, int offset, int size) {
        return selectList(Wrappers.<ConversationEntity>lambdaQuery()
                .eq(ConversationEntity::getUserId, userId)
                .isNull(ConversationEntity::getDeletedAt)
                .orderByDesc(ConversationEntity::getUpdatedAt)
                .orderByDesc(ConversationEntity::getId)
                .last("LIMIT " + offset + ", " + size));
    }

    /** 当前用户的有效会话总数（软删除的不计入）。 */
    default long countByUserId(Long userId) {
        return selectCount(Wrappers.<ConversationEntity>lambdaQuery()
                .eq(ConversationEntity::getUserId, userId)
                .isNull(ConversationEntity::getDeletedAt));
    }

    /** 软删除会话：只更新 deleted_at，行保留以维持外键；返回影响行数用于判断是否删除成功。 */
    @Update("UPDATE conversations SET deleted_at = #{now} WHERE id = #{id} AND deleted_at IS NULL")
    int softDeleteById(@Param("id") Long id, @Param("now") LocalDateTime now);
}
