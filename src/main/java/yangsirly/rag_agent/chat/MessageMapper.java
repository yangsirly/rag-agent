package yangsirly.rag_agent.chat;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

/**
 * messages 表的持久化边界。
 *
 * <p>
 * 幂等查询依赖 {@code (conversation_id, client_message_id)} 唯一约束对应的查找；
 * 历史分页采用“最新页优先”，实现时注意 SQL 的 ORDER BY / LIMIT 语义。
 * </p>
 */
@Mapper
public interface MessageMapper extends BaseMapper<MessageEntity> {

	/**
	 * 在同一会话内按客户端幂等键查找 USER 消息。
	 *
	 * @return 未发送过则返回 {@code null}
	 */
	default MessageEntity findUserMessageByClientMessageId(Long conversationId, String clientMessageId) {
		return selectOne(Wrappers.<MessageEntity>lambdaQuery()
				.eq(MessageEntity::getConversationId, conversationId)
				.eq(MessageEntity::getClientMessageId, clientMessageId)
				.eq(MessageEntity::getRole, MessageRole.USER));
	}

	/**
	 * 按“回复指向的 USER 消息 id”查找 ASSISTANT 消息。
	 *
	 * <p>
	 * 数据库对 {@code reply_to_message_id} 有唯一约束，因此最多一条。
	 * </p>
	 */
	default MessageEntity findAssistantByReplyTo(Long replyToMessageId) {
		return selectOne(Wrappers.<MessageEntity>lambdaQuery()
				.eq(MessageEntity::getReplyToMessageId, replyToMessageId)
				.eq(MessageEntity::getRole, MessageRole.ASSISTANT));
	}

	/** page=0 最新一页；用 createdAt DESC, id DESC + LIMIT 取页 */
	default List<MessageEntity> pageNewestFirst(Long conversationId, int page, int size) {
		int offset = page * size;
		return selectList(Wrappers.<MessageEntity>lambdaQuery()
				.eq(MessageEntity::getConversationId, conversationId)
				.orderByDesc(MessageEntity::getCreatedAt)
				.orderByDesc(MessageEntity::getId)
				.last("LIMIT " + offset + ", " + size));
	}

	default long countByConversationId(Long conversationId) {
		return selectCount(Wrappers.<MessageEntity>lambdaQuery()
				.eq(MessageEntity::getConversationId, conversationId));
	}
}
