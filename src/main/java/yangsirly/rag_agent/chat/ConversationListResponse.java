package yangsirly.rag_agent.chat;

import java.util.List;

/**
 * 会话列表分页响应。
 */
public record ConversationListResponse(
                int statusCode,
                List<ConversationViewDto> items,
                int page,
                int size,
                long totalElements,
                int totalPages) {

        /** 单条会话视图。 */
        public record ConversationViewDto(
                        String id,
                        String title,
                        String createdAt,
                        String updatedAt) {
        }
}
