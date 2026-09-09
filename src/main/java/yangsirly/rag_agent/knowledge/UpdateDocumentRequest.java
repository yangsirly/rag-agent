package yangsirly.rag_agent.knowledge;

/**
 * 修改文档请求入参 DTO。
 *
 * @param title 新标题（选填）
 * @param summary 新摘要（选填）
 * @param content 新正文（选填）
 */
public record UpdateDocumentRequest(
        String title,
        String summary,
        String content) {
}
