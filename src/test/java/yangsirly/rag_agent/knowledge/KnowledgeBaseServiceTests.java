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

class KnowledgeBaseServiceTests {

    private KnowledgeBaseMapper knowledgeBaseMapper;
    private DocumentMapper documentMapper;
    private UserMapper userMapper;
    private Clock clock;
    private KnowledgeBaseService knowledgeBaseService;

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-04T12:00:00Z");

    @BeforeEach
    void setUp() {
        knowledgeBaseMapper = mock(KnowledgeBaseMapper.class);
        documentMapper = mock(DocumentMapper.class);
        userMapper = mock(UserMapper.class);
        clock = Clock.fixed(FIXED_INSTANT, ZoneOffset.UTC);
        knowledgeBaseService = new KnowledgeBaseService(knowledgeBaseMapper, documentMapper, userMapper, clock);

        // 默认模拟已存在有效用户 ID=1L
        when(userMapper.selectById(1L)).thenReturn(mock(UserEntity.class));
    }

    @Test
    @DisplayName("创建知识库成功：用户存在且字段合法")
    void createSuccess() {
        CreateKnowledgeBaseRequest request = new CreateKnowledgeBaseRequest("产品研发规范", "说明文档");

        KnowledgeBaseEntity entity = knowledgeBaseService.create(1L, request);

        assertThat(entity.getCreatorId()).isEqualTo(1L);
        assertThat(entity.getName()).isEqualTo("产品研发规范");
        assertThat(entity.getDescription()).isEqualTo("说明文档");
        verify(knowledgeBaseMapper).insert(any(KnowledgeBaseEntity.class));
    }

    @Test
    @DisplayName("创建知识库失败：用户不存在抛出模糊报错")
    void createUserNotFoundFails() {
        when(userMapper.selectById(999L)).thenReturn(null);
        CreateKnowledgeBaseRequest request = new CreateKnowledgeBaseRequest("知识库", null);

        assertThatThrownBy(() -> knowledgeBaseService.create(999L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("请求参数非法");
    }

    @Test
    @DisplayName("创建知识库失败：名称为空或全空格")
    void createBlankNameFails() {
        CreateKnowledgeBaseRequest request = new CreateKnowledgeBaseRequest("   ", "描述");

        assertThatThrownBy(() -> knowledgeBaseService.create(1L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("请求参数非法");
    }

    @Test
    @DisplayName("创建知识库失败：名称超过 16 字限制")
    void createNameExceeds16CharsFails() {
        // 17 个汉字
        String longName = "这是一个超过十六个汉字的超长知识库名称呀";
        CreateKnowledgeBaseRequest request = new CreateKnowledgeBaseRequest(longName, null);

        assertThatThrownBy(() -> knowledgeBaseService.create(1L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("请求参数非法");
    }

    @Test
    @DisplayName("创建知识库失败：描述超过 100 字限制")
    void createDescExceeds100CharsFails() {
        String longDesc = "字".repeat(101);
        CreateKnowledgeBaseRequest request = new CreateKnowledgeBaseRequest("正常名称", longDesc);

        assertThatThrownBy(() -> knowledgeBaseService.create(1L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("请求参数非法");
    }

    @Test
    @DisplayName("获取详情成功：属于当前创建者")
    void getDetailSuccess() {
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity(1L, "研发规范", "说明", LocalDateTime.now(clock));
        entity.setId(10L);
        when(knowledgeBaseMapper.findByIdAndCreatorId(10L, 1L)).thenReturn(entity);

        KnowledgeBaseEntity result = knowledgeBaseService.getDetail(1L, 10L);

        assertThat(result.getId()).isEqualTo(10L);
        assertThat(result.getName()).isEqualTo("研发规范");
    }

    @Test
    @DisplayName("获取详情失败：不存在或非本人知识库抛出模糊报错")
    void getDetailNotFoundFails() {
        when(knowledgeBaseMapper.findByIdAndCreatorId(10L, 1L)).thenReturn(null);

        assertThatThrownBy(() -> knowledgeBaseService.getDetail(1L, 10L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("请求参数非法");
    }

    @Test
    @DisplayName("分页查询成功：参数合法并正确调用 mapper")
    void listSuccess() {
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity(1L, "研发规范", null, LocalDateTime.now(clock));
        when(knowledgeBaseMapper.listByCreatorId(1L, 0, 20)).thenReturn(List.of(entity));
        when(knowledgeBaseMapper.countByCreatorId(1L)).thenReturn(1L);

        var page = knowledgeBaseService.list(1L, 0, 20);

        assertThat(page.items()).hasSize(1);
        assertThat(page.totalElements()).isEqualTo(1L);
        assertThat(page.totalPages()).isEqualTo(1);
    }

    @Test
    @DisplayName("分页查询失败：页码超过 100 页限制拦截")
    void listPageOutOfRangeFails() {
        assertThatThrownBy(() -> knowledgeBaseService.list(1L, 100, 20))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("请求参数非法");
    }

    @Test
    @DisplayName("分页查询失败：单页条数超过 100 条限制拦截")
    void listSizeOutOfRangeFails() {
        assertThatThrownBy(() -> knowledgeBaseService.list(1L, 0, 101))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("请求参数非法");
    }

    @Test
    @DisplayName("修改知识库成功：更新名称")
    void updateSuccess() {
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity(1L, "旧名称", "描述", LocalDateTime.now(clock));
        entity.setId(10L);
        when(knowledgeBaseMapper.findByIdAndCreatorId(10L, 1L)).thenReturn(entity);

        UpdateKnowledgeBaseRequest request = new UpdateKnowledgeBaseRequest("新名称", null);
        KnowledgeBaseEntity updated = knowledgeBaseService.update(1L, 10L, request);

        assertThat(updated.getName()).isEqualTo("新名称");
        verify(knowledgeBaseMapper).updateById(entity);
    }

    @Test
    @DisplayName("软删除成功：调用 mapper softDeleteById")
    void deleteSuccess() {
        when(knowledgeBaseMapper.softDeleteById(eq(10L), any(LocalDateTime.class))).thenReturn(1);

        knowledgeBaseService.delete(1L, 10L);

        verify(knowledgeBaseMapper).softDeleteById(eq(10L), any(LocalDateTime.class));
        verify(documentMapper).softDeleteByKnowledgeBaseId(eq(10L), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("软删除失败：库不存在或非本人或已删除（影响行数0）")
    void deleteNotFoundFails() {
        when(knowledgeBaseMapper.softDeleteById(eq(10L), any(LocalDateTime.class))).thenReturn(0);

        assertThatThrownBy(() -> knowledgeBaseService.delete(1L, 10L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("请求参数非法");
    }
}
