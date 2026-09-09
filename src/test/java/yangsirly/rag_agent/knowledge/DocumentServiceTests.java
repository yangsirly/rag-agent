package yangsirly.rag_agent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import yangsirly.rag_agent.registration.UserEntity;
import yangsirly.rag_agent.registration.UserMapper;

class DocumentServiceTests {

    private DocumentMapper documentMapper;
    private KnowledgeBaseMapper knowledgeBaseMapper;
    private UserMapper userMapper;
    private Clock clock;
    private DocumentService documentService;

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-04T12:00:00Z");

    @BeforeEach
    void setUp() {
        documentMapper = mock(DocumentMapper.class);
        knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        userMapper = mock(UserMapper.class);
        clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        documentService = new DocumentService(
                documentMapper,
                knowledgeBaseMapper,
                userMapper,
                clock);

        // 默认用户存在且知识库属于该用户
        when(userMapper.selectById(1L)).thenReturn(mock(UserEntity.class));
        when(knowledgeBaseMapper.findByIdAndCreatorId(10L, 1L)).thenReturn(mock(KnowledgeBaseEntity.class));
    }

    @Test
    @DisplayName("创建文档成功：正文字数自动统计")
    void createDocumentSuccess() {
        CreateDocumentRequest request = new CreateDocumentRequest("员工守则", "公司规章", "这是正文内容");

        DocumentEntity entity = documentService.create(1L, 10L, request);

        assertThat(entity.getKnowledgeBaseId()).isEqualTo(10L);
        assertThat(entity.getCreatorId()).isEqualTo(1L);
        assertThat(entity.getTitle()).isEqualTo("员工守则");
        assertThat(entity.getContent()).isEqualTo("这是正文内容");
        assertThat(entity.getContentLength()).isEqualTo("这是正文内容".length());
        verify(documentMapper).insert(any(DocumentEntity.class));

    }

    @Test
    @DisplayName("创建文档失败：试图向不存在或他人的知识库添加文档拦截")
    void createDocumentUnownedKnowledgeBaseFails() {
        when(knowledgeBaseMapper.findByIdAndCreatorId(99L, 1L)).thenReturn(null);
        CreateDocumentRequest request = new CreateDocumentRequest("文档", null, "正文");

        assertThatThrownBy(() -> documentService.create(1L, 99L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("请求参数非法");
    }

    @Test
    @DisplayName("创建文档失败：标题超过 100 字")
    void createDocumentTitleTooLongFails() {
        String longTitle = "字".repeat(101);
        CreateDocumentRequest request = new CreateDocumentRequest(longTitle, null, "正文");

        assertThatThrownBy(() -> documentService.create(1L, 10L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("请求参数非法");
    }

    @Test
    @DisplayName("创建文档失败：正文超过 50,000 字")
    void createDocumentContentTooLongFails() {
        String longContent = "字".repeat(50001);
        CreateDocumentRequest request = new CreateDocumentRequest("标题", null, longContent);

        assertThatThrownBy(() -> documentService.create(1L, 10L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("请求参数非法");
    }

    @Test
    @DisplayName("查看文档详情成功")
    void getDetailSuccess() {
        DocumentEntity doc = new DocumentEntity(10L, 1L, "标题", "摘要", "正文", 2, LocalDateTime.now(clock));
        doc.setId(100L);
        when(documentMapper.findByIdAndKnowledgeBaseId(100L, 10L)).thenReturn(doc);

        DocumentEntity result = documentService.getDetail(1L, 10L, 100L);

        assertThat(result.getId()).isEqualTo(100L);
        assertThat(result.getTitle()).isEqualTo("标题");
    }

    @Test
    @DisplayName("查看文档列表成功：分页正常返回")
    void listDocumentsSuccess() {
        DocumentEntity doc = new DocumentEntity(10L, 1L, "标题", null, "正文", 2, LocalDateTime.now(clock));
        when(documentMapper.listByKnowledgeBaseId(10L, 0, 20)).thenReturn(List.of(doc));
        when(documentMapper.countByKnowledgeBaseId(10L)).thenReturn(1L);

        var page = documentService.list(1L, 10L, 0, 20);

        assertThat(page.items()).hasSize(1);
        assertThat(page.totalElements()).isEqualTo(1L);
    }

    @Test
    @DisplayName("修改文档成功：正文和 updated_at 同步更新")
    void updateDocumentSuccess() {
        DocumentEntity doc = new DocumentEntity(10L, 1L, "旧标题", null, "旧正文", 3, LocalDateTime.now(clock));
        doc.setId(100L);
        when(documentMapper.findByIdAndKnowledgeBaseId(100L, 10L)).thenReturn(doc);

        UpdateDocumentRequest request = new UpdateDocumentRequest("新标题", null, "全新的正文内容");
        DocumentEntity updated = documentService.update(1L, 10L, 100L, request);

        assertThat(updated.getTitle()).isEqualTo("新标题");
        assertThat(updated.getContent()).isEqualTo("全新的正文内容");
        assertThat(updated.getContentLength()).isEqualTo("全新的正文内容".length());
        verify(documentMapper).updateById(doc);

    }

    @Test
    @DisplayName("软删除文档成功：只标记 Document，不同步清理 chunk")
    void deleteDocumentSuccess() {
        when(documentMapper.softDeleteById(eq(100L), any(LocalDateTime.class))).thenReturn(1);

        documentService.delete(1L, 10L, 100L);

        verify(documentMapper).softDeleteById(eq(100L), any(LocalDateTime.class));
    }
}
