package yangsirly.rag_agent.chat;

/**
 * 发送消息的业务命令。
 *
 * <p>
 * userId 来自已认证主体，不能信任客户端 body 中的任何身份字段。
 * clientMessageId 由客户端为“一次发送动作”生成，超时重试必须复用同一值。
 * </p>
 * 
 * @param userId          已认证主体的用户 ID
 * @param conversationId  会话 ID
 * @param clientMessageId 客户端生成的消息唯一 ID（UUID 字符串）
 * @param content         用户消息内容
 */
public record SendMessageCommand(
		Long userId,
		Long conversationId,
		String clientMessageId,
		String content) {
}
