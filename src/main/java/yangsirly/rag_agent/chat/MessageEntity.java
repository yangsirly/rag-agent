package yangsirly.rag_agent.chat;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * messages 表的 MyBatis-Plus 映射。
 *
 * <p>USER 与 ASSISTANT 共用一张表，字段形态由数据库 CHECK 约束与业务层共同保证：
 * <ul>
 *   <li>USER：{@code clientMessageId} 非空，{@code replyToMessageId} 为空</li>
 *   <li>ASSISTANT：{@code clientMessageId} 为空，{@code replyToMessageId} 非空</li>
 * </ul>
 * </p>
 */
@TableName("messages")
public class MessageEntity {

	@TableId(type = IdType.AUTO)
	private Long id;

	@TableField("conversation_id")
	private Long conversationId;

	private MessageRole role;

	private String content;

	@TableField("client_message_id")
	private String clientMessageId;

	@TableField("reply_to_message_id")
	private Long replyToMessageId;

	@TableField("created_at")
	private LocalDateTime createdAt;

	/** MyBatis 查询结果映射需要无参构造器。 */
	protected MessageEntity() {
	}

	private MessageEntity(
			Long conversationId,
			MessageRole role,
			String content,
			String clientMessageId,
			Long replyToMessageId) {
		this.conversationId = conversationId;
		this.role = role;
		this.content = content;
		this.clientMessageId = clientMessageId;
		this.replyToMessageId = replyToMessageId;
	}

	/** 构造一条待插入的用户消息。 */
	public static MessageEntity userMessage(Long conversationId, String clientMessageId, String content) {
		return new MessageEntity(conversationId, MessageRole.USER, content, clientMessageId, null);
	}

	/** 构造一条待插入的助手回复，必须指向对应的 USER 消息主键。 */
	public static MessageEntity assistantReply(Long conversationId, Long replyToMessageId, String content) {
		return new MessageEntity(conversationId, MessageRole.ASSISTANT, content, null, replyToMessageId);
	}

	public Long getId() {
		return id;
	}

	public Long getConversationId() {
		return conversationId;
	}

	public MessageRole getRole() {
		return role;
	}

	public String getContent() {
		return content;
	}

	public String getClientMessageId() {
		return clientMessageId;
	}

	public Long getReplyToMessageId() {
		return replyToMessageId;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}
}
