package yangsirly.rag_agent.rag;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * P2.1 的确定性 baseline 分块器。
 *
 * <p>策略刻意保持简单：在最大 code point 长度内优先寻找最后一个空行分隔，
 * 找不到时退化到最后一个换行，再找不到才硬切。第一版不做 overlap，
 * 这样分块结果可预测，后续只有在评测证明确有边界损失时再引入重叠。</p>
 */
@Component
public class ParagraphChunker {

    public List<TextChunk> split(String content, int maxCodePoints) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content 不能为空");
        }
        if (maxCodePoints <= 0) {
            throw new IllegalArgumentException("maxCodePoints 必须大于 0");
        }

        List<TextChunk> chunks = new ArrayList<>();
        int cursor = skipWhitespaceForward(content, 0);
        int chunkIndex = 0;

        while (cursor < content.length()) {
            int remainingCodePoints = content.codePointCount(cursor, content.length());
            int hardEnd = remainingCodePoints <= maxCodePoints
                    ? content.length()
                    : content.offsetByCodePoints(cursor, maxCodePoints);

            int chosenEnd = hardEnd;
            int nextCursor = hardEnd;

            if (hardEnd < content.length()) {
                int paragraphBoundary = content.lastIndexOf("\n\n", hardEnd - 1);
                if (paragraphBoundary > cursor) {
                    chosenEnd = paragraphBoundary;
                    nextCursor = paragraphBoundary + 2;
                } else {
                    int lineBoundary = content.lastIndexOf('\n', hardEnd - 1);
                    if (lineBoundary > cursor) {
                        chosenEnd = lineBoundary;
                        nextCursor = lineBoundary + 1;
                    }
                }
            }

            int trimmedEnd = trimWhitespaceBackward(content, cursor, chosenEnd);
            if (trimmedEnd <= cursor) {
                // 防止文本边界刚好落在连续空白上导致空 chunk；此时按硬边界切。
                trimmedEnd = hardEnd;
                nextCursor = hardEnd;
            }

            int startOffset = content.codePointCount(0, cursor);
            int endOffset = content.codePointCount(0, trimmedEnd);
            String chunkContent = content.substring(cursor, trimmedEnd);

            chunks.add(new TextChunk(chunkIndex++, startOffset, endOffset, chunkContent));
            cursor = skipWhitespaceForward(content, nextCursor);
        }

        return List.copyOf(chunks);
    }

    private int skipWhitespaceForward(String content, int start) {
        int cursor = start;
        while (cursor < content.length()) {
            int codePoint = content.codePointAt(cursor);
            if (!Character.isWhitespace(codePoint)) {
                break;
            }
            cursor += Character.charCount(codePoint);
        }
        return cursor;
    }

    private int trimWhitespaceBackward(String content, int start, int end) {
        int cursor = end;
        while (cursor > start) {
            int codePoint = content.codePointBefore(cursor);
            if (!Character.isWhitespace(codePoint)) {
                break;
            }
            cursor -= Character.charCount(codePoint);
        }
        return cursor;
    }
}
