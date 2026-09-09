package yangsirly.rag_agent.rag;

/**
 * 一次分块产生的不可变结果。
 *
 * <p>startOffset / endOffset 与 documents.content_length 使用同一口径：Unicode code point。
 * startOffset 为 0-based inclusive，endOffset 为 exclusive，因此 endOffset - startOffset
 * 就是该 chunk 在原文中的 code point 长度。</p>
 */
public record TextChunk(
        int chunkIndex,
        int startOffset,
        int endOffset,
        String content) {
}
