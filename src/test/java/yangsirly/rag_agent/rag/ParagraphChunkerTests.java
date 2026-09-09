package yangsirly.rag_agent.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class ParagraphChunkerTests {

    private final ParagraphChunker chunker = new ParagraphChunker();

    @Test
    void prefersParagraphBoundaryBeforeHardCut() {
        String content = "第一段内容\n\n第二段内容";

        List<TextChunk> chunks = chunker.split(content, 6);

        assertThat(chunks).containsExactly(
                new TextChunk(0, 0, 5, "第一段内容"),
                new TextChunk(1, 7, 12, "第二段内容"));
    }

    @Test
    void hardCutsSingleLongParagraph() {
        String content = "ABCDEFGHIJ";

        List<TextChunk> chunks = chunker.split(content, 4);

        assertThat(chunks).containsExactly(
                new TextChunk(0, 0, 4, "ABCD"),
                new TextChunk(1, 4, 8, "EFGH"),
                new TextChunk(2, 8, 10, "IJ"));
    }

    @Test
    void offsetsUseUnicodeCodePointsInsteadOfUtf16CodeUnits() {
        String content = "甲😀乙丙丁";

        List<TextChunk> chunks = chunker.split(content, 3);

        assertThat(chunks).containsExactly(
                new TextChunk(0, 0, 3, "甲😀乙"),
                new TextChunk(1, 3, 5, "丙丁"));
    }

    @Test
    void rejectsBlankContentAndInvalidMaxSize() {
        assertThatThrownBy(() -> chunker.split("   ", 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("content 不能为空");

        assertThatThrownBy(() -> chunker.split("正文", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("maxCodePoints 必须大于 0");
    }
}
