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
 * 大模型这条路径的契约测试，三块：
 *
 * <p><b>① 升级制</b>——规则没把握时才把判断交给大模型，有把握时**一次请求都不发**。
 *
 * <p><b>为什么必须用计数传输层</b>：这条不变式的断言对象是"到底有没有发 HTTP 请求"，
 * 而不是"返回了什么"。只看响应体会得到一个**恒真**的测试——规则命中时 `engine` 本来就是
 * `RULE`，模型调没调它都是 `RULE`（模型返回空列表时也一样）。这与真机探针里
 * 「非报修职能一次 `/assistant/suggest` 都没发」必须用 CDP `Network` 域取证、而不是看 DOM
 * 是同一个道理。
 *
 * <p><b>② 合并策略</b>——模型返回的**顺序不参与排序**（根治"首候选随机翻"），
 * 且模型**不得淘汰规则命中**（只能填空位）。这两条也只在"模型真的被调了、且返回了东西"
 * 时才成立，所以同样需要计数传输层来确认用例没有空转。
 *
 * <p><b>③ `engine` 语义</b>——它记的是「模型有没有**贡献**可用结果」，**不是**「模型有没有被调」。
 * 模型返回的 id 全是规则已命中的、或候选位已被规则占满时，一个候选都不会多，`engine` 必须
 * 如实保持 `RULE`（"调了但没用上"与"没调"对外是同一件事；而"返回空列表不算贡献"是明确写下的判据，
 * 这是同一口径在"非空但无用"上的推广）。**所以任何"模型被调了没有"的断言都不能拿 `engine` 当代理**
 * ——那是恒真/恒假信号，只能看 `calls`。
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
    /** 本用例的唯一后缀：同一 JVM 共用一个 H2 库，靠它保证只命中本类自己造的细类 */
    private String unique;

    @BeforeEach
    void setUp() {
        assertThat(llmTransport)
                .as("@Primary 的计数传输层必须生效，否则本类断言的是空气")
                .isInstanceOf(CountingTransport.class);
        transport = (CountingTransport) llmTransport;
        transport.calls.set(0);

        unique = "ES" + SEQUENCE.incrementAndGet();

        Long parentId = createParent("水暖管道" + unique);
        leafId = createLeaf(parentId, "水龙头漏水" + unique, "滴水" + unique + ",关不紧", 0);
        leafName = "水龙头漏水" + unique;
        weakHitText = "滴水" + unique;

        // 打开大模型 + 升级阈值 10（非 HIGH 就升级）
        configService.updateConfig("assistant_llm_enabled", "1");
        configService.updateConfig("assistant_llm_escalate_below", "10");

        // 模型一律回这个叶子：这样 engine 变 RULE+LLM 就等价于"模型的答案被采纳了"
        transport.response = envelope(leafId);
    }

    private Long createParent(String name) {
        RepairCategory parent = new RepairCategory();
        parent.setName(name);
        parent.setLevel(1);
        parent.setSlaHours(24);
        return categoryService.createCategory(parent).getId();
    }

    /**
     * 建一道可被推荐的细类。
     *
     * @param keywords  助手同义词串；{@code null} 表示没有同义词
     * @param sortOrder 排序号——同分候选就是靠它定先后，所以造用例时必须显式给
     */
    private Long createLeaf(Long parentId, String name, String keywords, Integer sortOrder) {
        RepairCategory leaf = new RepairCategory();
        leaf.setParentId(parentId);
        leaf.setName(name);
        leaf.setWorkerSkillCode("PLUMBER");
        leaf.setSlaHours(24);
        leaf.setKeywords(keywords);
        leaf.setSortOrder(sortOrder);
        return categoryService.createCategory(leaf).getId();
    }

    private static String envelope(long categoryId) {
        return envelope(List.of(categoryId));
    }

    /** 造一个 chat/completions 信封，content 里是按给定顺序排列的 id 数组。 */
    private static String envelope(List<Long> categoryIds) {
        StringBuilder ids = new StringBuilder();
        for (Long id : categoryIds) {
            if (ids.length() > 0) {
                ids.append(',');
            }
            ids.append(id);
        }
        return "{\"choices\":[{\"message\":{\"content\":\"{\\\"categoryIds\\\":[" + ids + "]}\"}}]}";
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
    @DisplayName("弱命中（只命中 1 个关键词 = MEDIUM/5）也升级给模型")
    void weakHitEscalatesToModel() throws Exception {
        suggest(weakHitText)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidates[0].categoryId").value(leafId.intValue()))
                // 本用例的模型答的是**规则已经命中的那一条**（见 @BeforeEach 的 transport.response），
                // 一个候选都没多 ⇒ 按该口径它没贡献，engine 如实保持 RULE。
                // 「模型被调了」由下面的 calls 断言，不再由 engine 兼任（那是恒真代理，见类注释）。
                .andExpect(jsonPath("$.data.engine").value("RULE"));

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
                // 调是调了，但模型答的还是规则已命中的那条 ⇒ 无贡献 ⇒ RULE。
                // 这条用例的断言对象本来就是 calls（engine 在这里区分不出"调没调"）。
                .andExpect(jsonPath("$.data.engine").value("RULE"));

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

    // ------------------------------------------------------------------
    // 合并策略：模型返回顺序不参与排序 + 不得淘汰规则命中
    // ------------------------------------------------------------------

    @Test
    @DisplayName("模型返回顺序不影响候选顺序——同一个集合换个返回顺序，候选逐位相同")
    void modelReturnOrderDoesNotAffectCandidates() throws Exception {
        Long parentId = createParent("水暖管道ORD" + unique);
        // 两道规则一条都没命中的细类（关键词、叶子名、大类名都不出现在描述里）
        Long extraEarly = createLeaf(parentId, "地漏返味A" + unique, "地漏" + unique, 5);
        Long extraLate = createLeaf(parentId, "热水器不热B" + unique, "热水器" + unique, 9);

        // ① 模型按「先 early 后 late」返回
        transport.response = envelope(List.of(extraEarly, extraLate));
        suggest(weakHitText)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.engine").value("RULE+LLM"))
                // 规则命中(5 分, sortOrder 0) 在最前，两条模型补的按 sortOrder 排
                .andExpect(jsonPath("$.data.candidates[0].categoryId").value(leafId.intValue()))
                .andExpect(jsonPath("$.data.candidates[1].categoryId").value(extraEarly.intValue()))
                .andExpect(jsonPath("$.data.candidates[2].categoryId").value(extraLate.intValue()));

        // ② 模型把顺序倒过来返回 —— 正是思考模型 temperature 被服务端强制成 1.0 时发生的事。
        //    若 merge 拿模型序当主键，这里第 2/3 位就会跟着翻（本轮要根治的缺陷）。
        transport.response = envelope(List.of(extraLate, extraEarly));
        suggest(weakHitText)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidates[0].categoryId").value(leafId.intValue()))
                .andExpect(jsonPath("$.data.candidates[1].categoryId").value(extraEarly.intValue()))
                .andExpect(jsonPath("$.data.candidates[2].categoryId").value(extraLate.intValue()));

        assertThat(transport.calls.get())
                .as("两次都必须真的问过模型，否则本用例断言的是空气")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("规则命中占满名额时模型只能填空位——一条规则命中都不会被淘汰")
    void modelCannotEvictRuleHits() throws Exception {
        configService.updateConfig("assistant_max_candidates", "3");
        Long parentId = createParent("门窗锁具EV" + unique);
        Long hitA = createLeaf(parentId, "门锁损坏A" + unique, "锁芯" + unique, 1);
        Long hitB = createLeaf(parentId, "窗玻璃破B" + unique, "玻璃" + unique, 2);
        Long hitC = createLeaf(parentId, "纱窗破损C" + unique, "纱窗" + unique, 3);
        // 模型挑的是另外三道——规则一条都没命中它们
        Long otherX = createLeaf(parentId, "窗帘轨道X" + unique, "窗帘" + unique, 4);
        Long otherY = createLeaf(parentId, "晾衣架松Y" + unique, "晾衣架" + unique, 5);
        Long otherZ = createLeaf(parentId, "镜子裂了Z" + unique, "镜子" + unique, 6);
        transport.response = envelope(List.of(otherX, otherY, otherZ));

        // 三个关键词都命中 ⇒ 规则已经占满 3 个名额。此前的实现会把模型挑的排前面再截断，
        // 于是这三条规则命中被整批截掉 —— 「开了大模型反而更差」。
        suggest("锁芯" + unique + "玻璃" + unique + "纱窗" + unique)
                .andExpect(status().isOk())
                // 名额已满 ⇒ 模型这三条一条都进不来 ⇒ 它没贡献，engine 保持 RULE。
                // 这条断言与下面的 calls 合起来才完整：**调了模型，但一个候选都没采纳**。
                .andExpect(jsonPath("$.data.engine").value("RULE"))
                .andExpect(jsonPath("$.data.candidates.length()").value(3))
                .andExpect(jsonPath("$.data.candidates[0].categoryId").value(hitA.intValue()))
                .andExpect(jsonPath("$.data.candidates[1].categoryId").value(hitB.intValue()))
                .andExpect(jsonPath("$.data.candidates[2].categoryId").value(hitC.intValue()));

        assertThat(transport.calls.get()).as("模型确实被调了，只是它的答案没进候选").isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // engine 语义：它记的是「模型有没有**贡献**」，不是「模型有没有被调」
    // ------------------------------------------------------------------

    @Test
    @DisplayName("engine 记的是「贡献」不是「调用」：模型答规则已命中的 ⇒ RULE；补出新细类 ⇒ RULE+LLM")
    void engineReflectsContributionNotInvocation() throws Exception {
        Long parentId = createParent("engine语义EN" + unique);
        Long extra = createLeaf(parentId, "地漏返味EN" + unique, "地漏" + unique, 5);

        // ① 模型答的是规则**已经命中**的那条（@BeforeEach 里的 transport.response = envelope(leafId)）：
        //    问了，但一个候选都没多 ⇒ 与"没调"对外是同一件事，engine 必须是 RULE。
        // 这正是「engine = 模型有没有贡献可用结果」在"非空但无用"上的推广——
        //    此前实现只要 ids 非空就置 RULE+LLM，于是这条用例会红。
        suggest(weakHitText)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.engine").value("RULE"))
                .andExpect(jsonPath("$.data.candidates.length()").value(1))
                .andExpect(jsonPath("$.data.candidates[0].categoryId").value(leafId.intValue()));

        // ② 换成一道规则没命中的细类：候选多了一条 ⇒ 真的贡献了 ⇒ RULE+LLM
        transport.response = envelope(extra);
        suggest(weakHitText)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.engine").value("RULE+LLM"))
                .andExpect(jsonPath("$.data.candidates.length()").value(2))
                .andExpect(jsonPath("$.data.candidates[0].categoryId").value(leafId.intValue()))
                .andExpect(jsonPath("$.data.candidates[1].categoryId").value(extra.intValue()));

        assertThat(transport.calls.get())
                .as("两次都必须真的问过模型——差别只在模型答的东西有没有用")
                .isEqualTo(2);
    }
}
