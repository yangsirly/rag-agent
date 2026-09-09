package yangsirly.rag_agent.knowledge;

/**
 * 创建文档请求入参 DTO。
 *
 * @param title 文档标题，必填，1～100 字符
 * @param summary 摘要，选填，最多 500 字符
 * @param content 正文，必填，1～50,000 字符
 */
public record CreateDocumentRequest(
        String title,
        String summary,
        String content) {
}
