package yangsirly.rag_agent.chat;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * messages 表的 MyBatis-Plus 映射。
 *
 * <p>
 * USER 与 ASSISTANT 共用一张表，字段形态由数据库 CHECK 约束与业务层共同保证：
 * <ul>
 * <li>USER：{@code clientMessageId} 非空，{@code replyToMessageId} 为空</li>
 * <li>ASSISTANT：{@code clientMessageId} 为空，{@code replyToMessageId} 非空</li>
 * </ul>
 * 学习笔记：docs/learning/milestone-06-industrial-hardening.md#3.1 软删除与状态
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

    // 工业级：软删除时间，NULL=有效；V3 新增
    @TableField("deleted_at")
    private LocalDateTime deletedAt;

    // 工业级：消息状态 DONE/PENDING，预留异步模型调用；V4 新增
    @TableField("status")
    private String status;

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
        this.status = "DONE";
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

    /** 写入创建时间，保障同批消息排序稳定。 */
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getDeletedAt() {
        return deletedAt;
    }

    /** 软删除消息时写入删除时间。 */
    public void setDeletedAt(LocalDateTime deletedAt) {
        this.deletedAt = deletedAt;
    }

    public String getStatus() {
        return status;
    }

    /** 更新消息状态（预留异步模型调用阶段）。 */
    public void setStatus(String status) {
        this.status = status;
    }
}
