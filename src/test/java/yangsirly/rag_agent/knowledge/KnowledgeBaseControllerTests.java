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
 * 知识库 HTTP 接口集成测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
class KnowledgeBaseControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("未登录访问受保护知识库接口返回 401 UNAUTHORIZED")
    void unauthenticatedAccessReturns401() throws Exception {
        mockMvc.perform(get("/knowledge-bases"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    @DisplayName("CUSTOMER 角色访问知识库管理接口返回 403 FORBIDDEN")
    void customerRoleAccessReturns403() throws Exception {
        Cookie customerCookie = registerAndLogin(false);

        mockMvc.perform(get("/knowledge-bases").cookie(customerCookie))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(post("/knowledge-bases")
                .cookie(customerCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"试图越权创建\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("EDITOR 角色可成功创建知识库返回 201")
    void editorCanCreateKnowledgeBase() throws Exception {
        Cookie editorCookie = registerAndLogin(true);

        mockMvc.perform(post("/knowledge-bases")
                .cookie(editorCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                            "name": "产品研发规范",
                            "description": "说明文档"
                        }
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statusCode").value(201))
                .andExpect(jsonPath("$.name").value("产品研发规范"))
                .andExpect(jsonPath("$.description").value("说明文档"))
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.creatorId").isString());
    }

    @Test
    @DisplayName("创建知识库失败：名称超长返回 400 且模糊报错")
    void createNameTooLongReturns400() throws Exception {
        Cookie editorCookie = registerAndLogin(true);
        String tooLongName = "这是一个超过十六个汉字的超长知识库名称呀";

        mockMvc.perform(post("/knowledge-bases")
                .cookie(editorCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"%s\"}".formatted(tooLongName)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.statusCode").value(400))
                .andExpect(jsonPath("$.message").value("请求参数非法"));
    }

    @Test
    @DisplayName("查看详情与分页列表成功")
    void getDetailAndListSuccess() throws Exception {
        Cookie editorCookie = registerAndLogin(true);

        MvcResult createResult = mockMvc.perform(post("/knowledge-bases")
                .cookie(editorCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"我的知识库\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        String kbId = objectMapper.readTree(createResult.getResponse().getContentAsString()).path("id").asText();

        // 查看详情
        mockMvc.perform(get("/knowledge-bases/{id}", kbId).cookie(editorCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(kbId))
                .andExpect(jsonPath("$.name").value("我的知识库"));

        // 分页列表
        MvcResult listResult = mockMvc.perform(get("/knowledge-bases").cookie(editorCookie))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode items = objectMapper.readTree(listResult.getResponse().getContentAsString()).path("items");
        assertThat(items.size()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("修改知识库成功")
    void updateKnowledgeBaseSuccess() throws Exception {
        Cookie editorCookie = registerAndLogin(true);

        MvcResult createResult = mockMvc.perform(post("/knowledge-bases")
                .cookie(editorCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"旧库名\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        String kbId = objectMapper.readTree(createResult.getResponse().getContentAsString()).path("id").asText();

        mockMvc.perform(patch("/knowledge-bases/{id}", kbId)
                .cookie(editorCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"新库名\",\"description\":\"新描述\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("新库名"))
                .andExpect(jsonPath("$.description").value("新描述"));
    }

    @Test
    @DisplayName("软删除知识库成功返回 204，再次查询返回 400")
    void deleteKnowledgeBaseSuccess() throws Exception {
        Cookie editorCookie = registerAndLogin(true);

        MvcResult createResult = mockMvc.perform(post("/knowledge-bases")
                .cookie(editorCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"待删除库\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        String kbId = objectMapper.readTree(createResult.getResponse().getContentAsString()).path("id").asText();

        // 软删除返回 204
        mockMvc.perform(delete("/knowledge-bases/{id}", kbId).cookie(editorCookie))
                .andExpect(status().isNoContent());

        // 再次查看详情，返回 400
        mockMvc.perform(get("/knowledge-bases/{id}", kbId).cookie(editorCookie))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请求参数非法"));
    }

    @Test
    @DisplayName("访问他人知识库返回 400（模糊报错）")
    void accessOtherEditorKnowledgeBaseReturns400() throws Exception {
        Cookie editorA = registerAndLogin(true);
        Cookie editorB = registerAndLogin(true);

        MvcResult createResult = mockMvc.perform(post("/knowledge-bases")
                .cookie(editorA)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"A的私有库\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        String kbId = objectMapper.readTree(createResult.getResponse().getContentAsString()).path("id").asText();

        // Editor B 尝试访问 Editor A 的库，返回 400
        mockMvc.perform(get("/knowledge-bases/{id}", kbId).cookie(editorB))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请求参数非法"));
    }

    private Cookie registerAndLogin(boolean isEditor) throws Exception {
        String email = "kb-" + UUID.randomUUID() + "@example.com";
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
