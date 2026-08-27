package yangsirly.rag_agent.chat;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

/**
 * messages 表的持久化边界。
 *
 * <p>
 * 幂等查询依赖 {@code (conversation_id, client_message_id)} 唯一约束对应的查找；
 * 历史分页采用“最新页优先”，实现时注意 SQL 的 ORDER BY / LIMIT 语义。
 * 工业级：所有查询过滤 deleted_at IS NULL（软删除）。
 * </p>
 */
@Mapper
public interface MessageMapper extends BaseMapper<MessageEntity> {

    /**
     * 在同一会话内按客户端幂等键查找 USER 消息（仅有效消息）。
     */
    default MessageEntity findUserMessageByClientMessageId(Long conversationId, String clientMessageId) {
        return selectOne(Wrappers.<MessageEntity>lambdaQuery()
                .eq(MessageEntity::getConversationId, conversationId)
                .eq(MessageEntity::getClientMessageId, clientMessageId)
                .eq(MessageEntity::getRole, MessageRole.USER)
                .isNull(MessageEntity::getDeletedAt));
    }

    /**
     * 按“回复指向的 USER 消息 id”查找 ASSISTANT 消息。
     */
    default MessageEntity findAssistantByReplyTo(Long replyToMessageId) {
        return selectOne(Wrappers.<MessageEntity>lambdaQuery()
                .eq(MessageEntity::getReplyToMessageId, replyToMessageId)
                .eq(MessageEntity::getRole, MessageRole.ASSISTANT)
                .isNull(MessageEntity::getDeletedAt));
    }

    /** page=0 最新一页；用 createdAt DESC, id DESC + LIMIT 取页（仅有效消息） */
    default List<MessageEntity> pageNewestFirst(Long conversationId, int page, int size) {
        int offset = page * size;
        return selectList(Wrappers.<MessageEntity>lambdaQuery()
                .eq(MessageEntity::getConversationId, conversationId)
                .isNull(MessageEntity::getDeletedAt)
                .orderByDesc(MessageEntity::getCreatedAt)
                .orderByDesc(MessageEntity::getId)
                .last("LIMIT " + offset + ", " + size));
    }

    /** 游标分页：取 cursor 之后的消息（createdAt,id）> cursor 位置 */
    default List<MessageEntity> pageAfterCursor(Long conversationId, Long cursorId, int size) {
        MessageEntity cursor = selectById(cursorId);
        if (cursor == null) return List.of();
        return selectList(Wrappers.<MessageEntity>lambdaQuery()
                .eq(MessageEntity::getConversationId, conversationId)
                .isNull(MessageEntity::getDeletedAt)
                .and(w -> w.gt(MessageEntity::getCreatedAt, cursor.getCreatedAt())
                        .or().eq(MessageEntity::getCreatedAt, cursor.getCreatedAt()).gt(MessageEntity::getId, cursor.getId()))
                .orderByAsc(MessageEntity::getCreatedAt)
                .orderByAsc(MessageEntity::getId)
                .last("LIMIT " + size));
    }

    default long countByConversationId(Long conversationId) {
        return selectCount(Wrappers.<MessageEntity>lambdaQuery()
                .eq(MessageEntity::getConversationId, conversationId)
                .isNull(MessageEntity::getDeletedAt));
    }

    /** 软删除：批量将 messages.deleted_at 置为当前时间（分批调用方控制） */
    @Update("UPDATE messages SET deleted_at = #{now} WHERE conversation_id = #{conversationId} AND deleted_at IS NULL LIMIT #{limit}")
    int softDeleteBatch(@Param("conversationId") Long conversationId, @Param("now") LocalDateTime now, @Param("limit") int limit);

    /** 已软删消息的物理清理（可选后台任务） */
    default int hardDeleteSoftDeleted(Long conversationId) {
        return delete(Wrappers.<MessageEntity>lambdaQuery()
                .eq(MessageEntity::getConversationId, conversationId)
                .isNotNull(MessageEntity::getDeletedAt));
    }
}
