package yangsirly.rag_agent.chat;

/**
 * 创建会话的 HTTP 请求体。
 *
 * <p>title 可选：缺省或 null 时服务端使用默认标题；
 * 若提供则在 Service 中校验 1～100 字（不能只靠 Bean Validation，
 * 因为“可缺省”与“出现后不能空白”需要业务层区分）。</p>
 */
public record CreateConversationRequest(String title) {
}
