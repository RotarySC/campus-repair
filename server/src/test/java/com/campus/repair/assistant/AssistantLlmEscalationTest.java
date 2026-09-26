package com.campus.repair.assistant;

import com.campus.repair.framework.util.JwtUtils;
import com.campus.repair.modules.assistant.llm.AssistantLlmTransport;
import com.campus.repair.modules.spatial.entity.RepairCategory;
import com.campus.repair.modules.spatial.service.RepairCategoryService;
import com.campus.repair.modules.spatial.service.SysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * **升级制**的契约测试：规则没把握时才把判断交给大模型，有把握时**一次请求都不发**。
 *
 * <p><b>为什么必须用计数传输层</b>：这条不变式的断言对象是"到底有没有发 HTTP 请求"，
 * 而不是"返回了什么"。只看响应体会得到一个**恒真**的测试——规则命中时 `engine` 本来就是
 * `RULE`，模型调没调它都是 `RULE`（模型返回空列表时也一样）。这与真机探针里
 * 「非报修职能一次 `/assistant/suggest` 都没发」必须用 CDP `Network` 域取证、而不是看 DOM
 * 是同一个道理（见 `AGENTS.md` §2.2）。
 *
 * <p>本类通过 `@SpringBootTest(properties = …)` 打开大模型（否则 `isLlmEnabled()` 恒假、
 * 测试会**空转通过**），并用一个 `@Primary` 的计数实现替换真实传输层——
 * 所以它跑在**自己的 Spring 上下文**里，不会污染 `AssistantApiIntegrationTest`
 * 对 `llmReady=false` 的断言。`sys_config` 的写入在 `@Transactional` 内，同样会回滚。
 */
@SpringBootTest(properties = {
        "app.assistant.llm.enabled=true",
        "app.assistant.llm.base-url=http://127.0.0.1:1/v1",
        "app.assistant.llm.api-key=test-key",
        "app.assistant.llm.model=test-model"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AssistantLlmEscalationTest {

    private static final long STUDENT_ID = 3L;
    /** 分类名带唯一后缀：同一 JVM 共用一个 H2 库，别的测试类建过同名分类不应影响这里 */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    /** 计数版传输层：本类的核心断言对象。 */
    static final class CountingTransport implements AssistantLlmTransport {

        final AtomicInteger calls = new AtomicInteger();
        volatile String response = "";

        @Override
        public String postJson(String url, String apiKey, String jsonBody) {
            calls.incrementAndGet();
            return response;
        }
    }

    @TestConfiguration
    static class CountingTransportConfig {
        @Bean
        @Primary
        AssistantLlmTransport countingTransport() {
            return new CountingTransport();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private RepairCategoryService categoryService;

    @Autowired
    private SysConfigService configService;

    /** 注入到的必须是计数实现——若 `@Primary` 没生效，本类会退化成空转测试 */
    @Autowired
    private AssistantLlmTransport llmTransport;

    private CountingTransport transport;
    private Long leafId;
    private String leafName;
    /** 只命中一个关键词（+5 = MEDIUM）的输入 */
    private String weakHitText;

    @BeforeEach
    void setUp() {
        assertThat(llmTransport)
                .as("@Primary 的计数传输层必须生效，否则本类断言的是空气")
                .isInstanceOf(CountingTransport.class);
        transport = (CountingTransport) llmTransport;
        transport.calls.set(0);

        String unique = "ES" + SEQUENCE.incrementAndGet();

        RepairCategory parent = new RepairCategory();
        parent.setName("水暖管道" + unique);
        parent.setLevel(1);
        parent.setSlaHours(24);
        Long parentId = categoryService.createCategory(parent).getId();

        RepairCategory leaf = new RepairCategory();
        leaf.setParentId(parentId);
        leaf.setName("水龙头漏水" + unique);
        leaf.setWorkerSkillCode("PLUMBER");
        leaf.setSlaHours(24);
        leaf.setKeywords("滴水" + unique + ",关不紧");
        leafId = categoryService.createCategory(leaf).getId();

        leafName = "水龙头漏水" + unique;
        weakHitText = "滴水" + unique;

        // 打开大模型 + 升级阈值 10（非 HIGH 就升级）
        configService.updateConfig("assistant_llm_enabled", "1");
        configService.updateConfig("assistant_llm_escalate_below", "10");

        // 模型一律回这个叶子：这样 engine 变 RULE+LLM 就等价于"模型的答案被采纳了"
        transport.response = envelope(leafId);
    }

    private static String envelope(long categoryId) {
        return "{\"choices\":[{\"message\":{\"content\":\"{\\\"categoryIds\\\":[" + categoryId + "]}\"}}]}";
    }

    private String bearer() {
        return "Bearer " + jwtUtils.generateAccessToken(STUDENT_ID, "student01", List.of("REPORTER"));
    }

    private org.springframework.test.web.servlet.ResultActions suggest(String description) throws Exception {
        return mockMvc.perform(post("/api/v1/assistant/suggest")
                .header("Authorization", bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":\"" + description + "\"}"));
    }

    // ------------------------------------------------------------------
    // 升级制
    // ------------------------------------------------------------------

    @Test
    @DisplayName("规则给出 HIGH 命中时，一次模型请求都不发（且这不算降级）")
    void highConfidenceSkipsModel() throws Exception {
        suggest(leafName)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.engine").value("RULE"))
                .andExpect(jsonPath("$.data.noMatch").value(false))
                // "没调模型"不是降级：degradedReason 必须是 null
                .andExpect(jsonPath("$.data.degradedReason").value(nullValue()));

        assertThat(transport.calls.get())
                .as("叶子名命中 = +10 = HIGH，规则有把握，不该调模型")
                .isZero();
    }

    @Test
    @DisplayName("一条都没命中（noMatch）时升级给模型，engine 变 RULE+LLM")
    void noMatchEscalatesToModel() throws Exception {
        suggest("今天天气不错，想去打球")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.engine").value("RULE+LLM"))
                .andExpect(jsonPath("$.data.candidates[0].categoryId").value(leafId.intValue()));

        assertThat(transport.calls.get()).as("noMatch 必须升级").isEqualTo(1);
    }

    @Test
    @DisplayName("弱命中（只命中 1 个关键词 = MEDIUM/5）也升级——实测那正是模型能补出更好答案的一档")
    void weakHitEscalatesToModel() throws Exception {
        suggest(weakHitText)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.engine").value("RULE+LLM"));

        assertThat(transport.calls.get())
                .as("5 < 10，低于阈值就该交给模型")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("阈值=0 时退化为「每次都调模型」（升级制之前的行为）")
    void thresholdZeroCallsModelEvenOnHighConfidence() throws Exception {
        configService.updateConfig("assistant_llm_escalate_below", "0");

        suggest(leafName)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.engine").value("RULE+LLM"));

        assertThat(transport.calls.get()).as("阈值 0 = 关闭升级制").isEqualTo(1);
    }

    @Test
    @DisplayName("阈值=1 时只有 noMatch 升级，弱命中自己扛")
    void thresholdOneOnlyEscalatesOnNoMatch() throws Exception {
        configService.updateConfig("assistant_llm_escalate_below", "1");

        suggest(weakHitText)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.engine").value("RULE"));
        assertThat(transport.calls.get()).as("3 分以上就不升级").isZero();

        suggest("今天天气不错，想去打球")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.engine").value("RULE+LLM"));
        assertThat(transport.calls.get()).as("只有这一条该升级").isEqualTo(1);
    }
}
