package com.campus.repair.review;

import com.campus.repair.modules.review.service.SensitiveWordService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 敏感词表维护端点（Phase 02）。
 *
 * <p>权限口径：整类只给 {@code PLATFORM_ADMIN}（系统参数性质），调度/维修/报修人一律 403。
 * 业务错误（重复词、非法分类）走 {@code R} 的业务码 + HTTP 200；Bean Validation 失败走 HTTP 400。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SensitiveWordApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SensitiveWordService sensitiveWordService;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @MockBean
    private ValueOperations<String, String> valueOperations;

    @BeforeEach
    void setUp() {
        Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    /**
     * 测试事务回滚后重建词表快照。
     *
     * <p>词表快照是**进程级**缓存：本类在事务内改词/停用后若手动刷新，缓存会拿到未提交数据；
     * 回滚后必须再刷一次，否则后续测试类会看到一条数据库里不存在的词。
     */
    @AfterEach
    void refreshCacheAfterRollback() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    sensitiveWordService.refresh();
                }
            });
        }
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        return readJson(result).path("data").path("accessToken").asText();
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    @Test
    @DisplayName("① PLATFORM_ADMIN 分页查询敏感词（种子四类共 9 条）")
    void adminCanListWords() throws Exception {
        String token = loginAndGetToken("admin", "password");

        mockMvc.perform(get("/api/v1/content-rules/words")
                        .header("Authorization", "Bearer " + token)
                        .param("page", "1")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(org.hamcrest.Matchers.greaterThanOrEqualTo(9)))
                .andExpect(jsonPath("$.data.records[0].word").isString())
                .andExpect(jsonPath("$.data.records[0].category").isString());
    }

    @Test
    @DisplayName("② 按分类过滤：只返回该类的词")
    void filterByCategory() throws Exception {
        String token = loginAndGetToken("admin", "password");

        MvcResult result = mockMvc.perform(get("/api/v1/content-rules/words")
                        .header("Authorization", "Bearer " + token)
                        .param("category", "AD")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();

        JsonNode records = readJson(result).path("data").path("records");
        assertThat(records).isNotEmpty();
        records.forEach(record -> assertThat(record.path("category").asText()).isEqualTo("AD"));
    }

    @Test
    @DisplayName("③ 新增敏感词 → 启用；重复词给业务错误")
    void createAndRejectDuplicate() throws Exception {
        String token = loginAndGetToken("admin", "password");
        String body = objectMapper.writeValueAsString(Map.of("word", "测试占位词A", "category", "AD"));

        MvcResult created = mockMvc.perform(post("/api/v1/content-rules/words")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.word").value("测试占位词A"))
                .andExpect(jsonPath("$.data.category").value("AD"))
                .andExpect(jsonPath("$.data.enabled").value(1))
                .andReturn();
        long id = readJson(created).path("data").path("id").asLong();
        assertThat(id).isPositive();

        mockMvc.perform(post("/api/v1/content-rules/words")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("已存在")));
    }

    @Test
    @DisplayName("④ 非法分类 / 空词给业务/参数校验错误")
    void invalidWordOrCategoryRejected() throws Exception {
        String token = loginAndGetToken("admin", "password");

        mockMvc.perform(post("/api/v1/content-rules/words")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("word", "占位词B", "category", "UNKNOWN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(post("/api/v1/content-rules/words")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("word", "  ", "category", "AD"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("⑤ 启停用：enabled=0 后初筛不再命中（快照刷新）")
    void disableWordStopsHit() throws Exception {
        String token = loginAndGetToken("admin", "password");
        assertThat(sensitiveWordService.containsHit("求代做课程设计")).isTrue();

        mockMvc.perform(put("/api/v1/content-rules/words/1")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enabled", 0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.enabled").value(0));

        // 事务内手动刷新，让快照看到本事务里尚未提交的停用（回滚后由 @AfterEach 再刷回）
        sensitiveWordService.refresh();
        assertThat(sensitiveWordService.containsHit("求代做课程设计")).isFalse();
    }

    @Test
    @DisplayName("⑥ 删除敏感词：物理删，列表不再出现")
    void deleteWord() throws Exception {
        String token = loginAndGetToken("admin", "password");

        MvcResult created = mockMvc.perform(post("/api/v1/content-rules/words")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("word", "测试占位词C", "category", "IRRELEVANT"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        long id = readJson(created).path("data").path("id").asLong();

        mockMvc.perform(delete("/api/v1/content-rules/words/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/v1/content-rules/words")
                        .header("Authorization", "Bearer " + token)
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[?(@.id == " + id + ")]").isEmpty());
    }

    @Test
    @DisplayName("⑦ 非 PLATFORM_ADMIN（维修工）维护词表 → 403")
    void nonAdminForbidden() throws Exception {
        String token = loginAndGetToken("worker01", "password");

        mockMvc.perform(post("/api/v1/content-rules/words")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("word", "越权词", "category", "AD"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));

        mockMvc.perform(get("/api/v1/content-rules/words")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }
}
