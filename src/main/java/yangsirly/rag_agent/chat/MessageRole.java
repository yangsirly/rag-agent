package yangsirly.rag_agent.chat;

/**
 * 消息角色，表示“谁说了这句话”。
 *
 * <p>注意与用户角色 {@code CUSTOMER / EDITOR} 区分：
 * 用户角色描述系统权限，消息角色描述对话中的发言方。</p>
 */
public enum MessageRole {
	/** 终端用户发出的消息。 */
	USER,
	/** 系统（第一阶段为固定模板）发出的回复。 */
	ASSISTANT
}
