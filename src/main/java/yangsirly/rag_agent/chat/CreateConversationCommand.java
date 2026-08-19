package yangsirly.rag_agent.chat;

/**
 * 创建会话的业务命令。
 *
 * <p>title 允许为 null，表示使用服务端默认标题；
 * 若提供则由 Service 做长度与空白校验。</p>
 */
public record CreateConversationCommand(Long userId, String title) {
}
