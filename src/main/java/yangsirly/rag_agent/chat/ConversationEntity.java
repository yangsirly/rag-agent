package yangsirly.rag_agent.chat;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * conversations 表的 MyBatis-Plus 映射。
 *
 * <p>
 * 只负责表字段与 Java 属性的对应；所有权校验、标题规则等业务放在 Service。
 * </p>
 */
@TableName("conversations")
public class ConversationEntity {

	@TableId(type = IdType.AUTO)
	private Long id;

	@TableField("user_id")
	private Long userId;

	private String title;

	@TableField("created_at")
	private LocalDateTime createdAt;

	@TableField("updated_at")
	private LocalDateTime updatedAt;

	/** 软删除时间，NULL=有效；V5 新增。删除会话只打标记，行保留以维持 messages 外键。 */
	@TableField("deleted_at")
	private LocalDateTime deletedAt;

	/** MyBatis 查询结果映射需要无参构造器。 */
	protected ConversationEntity() {
	}

	/**
	 * 业务侧创建会话实体时使用的最小构造器。
	 */
	public ConversationEntity(Long userId, String title) {
		this.userId = userId;
		this.title = title;
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}

	public LocalDateTime getDeletedAt() {
		return deletedAt;
	}

	/** 会话软删除打标。 */
	public void setDeletedAt(LocalDateTime deletedAt) {
		this.deletedAt = deletedAt;
	}

	/** 会话内容变更后刷新更新时间。 */
	public void setUpdatedAt(LocalDateTime updatedAt) {
		this.updatedAt = updatedAt;
	}

	/** 首次创建时写入创建时间。 */
	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}
}
