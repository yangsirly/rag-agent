package yangsirly.rag_agent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.Cookie;

/**
 * 文档模块 HTTP 接口集成测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DocumentControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("未登录访问受保护文档接口返回 401 UNAUTHORIZED")
    void unauthenticatedAccessReturns401() throws Exception {
        mockMvc.perform(get("/knowledge-bases/1/documents"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("CUSTOMER 角色访问文档管理接口返回 403 FORBIDDEN")
    void customerRoleAccessReturns403() throws Exception {
        Cookie customerCookie = registerAndLogin(false);

        mockMvc.perform(get("/knowledge-bases/1/documents").cookie(customerCookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("EDITOR 角色可成功在自己的知识库下创建文档返回 201")
    void editorCanCreateDocument() throws Exception {
        Cookie editorCookie = registerAndLogin(true);
        String kbId = createKnowledgeBase(editorCookie, "研发标准库");

        mockMvc.perform(post("/knowledge-bases/{kbId}/documents", kbId)
                .cookie(editorCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                            "title": "代码规范手册",
                            "summary": "Java 编码标准",
                            "content": "所有的业务代码必须经过单元测试覆盖。"
                        }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201))
                .andExpect(jsonPath("$.title").value("代码规范手册"))
                .andExpect(jsonPath("$.summary").value("Java 编码标准"))
                .andExpect(jsonPath("$.content").value("所有的业务代码必须经过单元测试覆盖。"))
                .andExpect(jsonPath("$.contentLength").value("所有的业务代码必须经过单元测试覆盖。".length()))
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.knowledgeBaseId").value(kbId));
    }

    @Test
    @DisplayName("查看详情包含完整大正文，而列表项彻底剥离大正文")
    void getDetailContainsContentWhileListOmitsContent() throws Exception {
        Cookie editorCookie = registerAndLogin(true);
        String kbId = createKnowledgeBase(editorCookie, "制度库");

        MvcResult docResult = mockMvc.perform(post("/knowledge-bases/{kbId}/documents", kbId)
                .cookie(editorCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                            "title": "请假管理规定",
                            "summary": "员工假期说明",
                            "content": "这是一份长达几千字的请假规范详细条目..."
                        }
                        """))
                .andExpect(status().isCreated())
                .andReturn();

        String docId = objectMapper.readTree(docResult.getResponse().getContentAsString()).path("id").asText();

        // 1. 验证详情接口返回完整正文
        mockMvc.perform(get("/knowledge-bases/{kbId}/documents/{docId}", kbId, docId).cookie(editorCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("这是一份长达几千字的请假规范详细条目..."));

        // 2. 验证列表接口剥离正文，仅包含字数与元数据
        MvcResult listResult = mockMvc.perform(get("/knowledge-bases/{kbId}/documents", kbId).cookie(editorCookie))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode items = objectMapper.readTree(listResult.getResponse().getContentAsString()).path("items");
        assertThat(items.size()).isGreaterThanOrEqualTo(1);
        JsonNode firstDoc = items.get(0);
        assertThat(firstDoc.has("content")).isFalse(); // 确认彻底剥离 content 字段！
        assertThat(firstDoc.path("contentLength").asInt()).isEqualTo("这是一份长达几千字的请假规范详细条目...".length());
    }

    @Test
    @DisplayName("修改文档成功（PATCH）")
    void updateDocumentSuccess() throws Exception {
        Cookie editorCookie = registerAndLogin(true);
        String kbId = createKnowledgeBase(editorCookie, "产品文档");

        MvcResult docResult = mockMvc.perform(post("/knowledge-bases/{kbId}/documents", kbId)
                .cookie(editorCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                            "title": "旧文档",
                            "content": "旧内容"
                        }
                        """))
                .andExpect(status().isCreated())
                .andReturn();

        String docId = objectMapper.readTree(docResult.getResponse().getContentAsString()).path("id").asText();

        mockMvc.perform(patch("/knowledge-bases/{kbId}/documents/{docId}", kbId, docId)
                .cookie(editorCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                            "title": "新文档标题",
                            "content": "更新后的详细新内容"
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("新文档标题"))
                .andExpect(jsonPath("$.content").value("更新后的详细新内容"))
                .andExpect(jsonPath("$.contentLength").value("更新后的详细新内容".length()));
    }

    @Test
    @DisplayName("软删除单篇文档成功返回 204，再次获取返回 400")
    void deleteDocumentSuccess() throws Exception {
        Cookie editorCookie = registerAndLogin(true);
        String kbId = createKnowledgeBase(editorCookie, "财务库");

        MvcResult docResult = mockMvc.perform(post("/knowledge-bases/{kbId}/documents", kbId)
                .cookie(editorCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                            "title": "报销凭据",
                            "content": "报销发票粘贴方法"
                        }
                        """))
                .andExpect(status().isCreated())
                .andReturn();

        String docId = objectMapper.readTree(docResult.getResponse().getContentAsString()).path("id").asText();

        // 删除单篇文档
        mockMvc.perform(delete("/knowledge-bases/{kbId}/documents/{docId}", kbId, docId).cookie(editorCookie))
                .andExpect(status().isNoContent());

        // 再次获取返回 400
        mockMvc.perform(get("/knowledge-bases/{kbId}/documents/{docId}", kbId, docId).cookie(editorCookie))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请求参数非法"));
    }

    @Test
    @DisplayName("知识库被删除后，下属文档级联软删除")
    void cascadeDeleteWhenKnowledgeBaseDeleted() throws Exception {
        Cookie editorCookie = registerAndLogin(true);
        String kbId = createKnowledgeBase(editorCookie, "即将被删的库");

        MvcResult docResult = mockMvc.perform(post("/knowledge-bases/{kbId}/documents", kbId)
                .cookie(editorCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                            "title": "从属文章",
                            "content": "宿主库被删除后我也会跟着软删除"
                        }
                        """))
                .andExpect(status().isCreated())
                .andReturn();

        String docId = objectMapper.readTree(docResult.getResponse().getContentAsString()).path("id").asText();

        // 1. 删除整个知识库
        mockMvc.perform(delete("/knowledge-bases/{kbId}", kbId).cookie(editorCookie))
                .andExpect(status().isNoContent());

        // 2. 验证该知识库下的文档也被级联拦截（返回 400）
        mockMvc.perform(get("/knowledge-bases/{kbId}/documents/{docId}", kbId, docId).cookie(editorCookie))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请求参数非法"));
    }

    @Test
    @DisplayName("试图往他人的知识库中创建文档被拦截返回 400")
    void createDocumentInOthersKnowledgeBaseReturns400() throws Exception {
        Cookie editorA = registerAndLogin(true);
        Cookie editorB = registerAndLogin(true);

        String kbA = createKnowledgeBase(editorA, "A的知识库");

        // Editor B 尝试向 Editor A 的库写入文档，被拦截
        mockMvc.perform(post("/knowledge-bases/{kbId}/documents", kbA)
                .cookie(editorB)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                            "title": "恶意注入文档",
                            "content": "试图写入他人库"
                        }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请求参数非法"));
    }

    // ------------------------------------------------------------------
    // 辅助方法
    // ------------------------------------------------------------------

    private String createKnowledgeBase(Cookie cookie, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/knowledge-bases")
                .cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"%s\"}".formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("id").asText();
    }

    private Cookie registerAndLogin(boolean isEditor) throws Exception {
        String email = "doc-" + UUID.randomUUID() + "@example.com";
        String password = "password-ok-1";

        mockMvc.perform(post("/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isCreated());

        if (isEditor) {
            jdbcTemplate.update("UPDATE users SET role = 'EDITOR' WHERE email = ?", email);
        }

        MvcResult login = mockMvc.perform(post("/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andExpect(status().isOk()).andReturn();

        Cookie cookie = login.getResponse().getCookie("access_token");
        assertThat(cookie).isNotNull();
        return cookie;
    }
}
