package com.campus.repair.assistant;

import com.campus.repair.framework.util.JwtUtils;
import com.campus.repair.modules.spatial.entity.RepairCategory;
import com.campus.repair.modules.spatial.entity.SpatialNode;
import com.campus.repair.modules.spatial.service.RepairCategoryService;
import com.campus.repair.modules.spatial.service.SpatialNodeService;
import com.campus.repair.modules.spatial.service.SysConfigService;
import com.campus.repair.modules.workorder.entity.RepairOrder;
import com.campus.repair.modules.workorder.mapper.RepairOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 智能助手接口契约测试。
 *
 * <p>锁死四件事：
 * <ol>
 *   <li><b>鉴权按职能分档</b>——推荐与进度速查只有报修人能用（403 而不是静默空结果）；</li>
 *   <li><b>推荐结果可解释且确定</b>——命中词、工种、SLA、置信度都要对得上；</li>
 *   <li><b>宁可不猜</b>——没命中就 noMatch + 大类回退，凭空的候选一条都不给；</li>
 *   <li><b>制度问答的数值来自系统参数</b>——测试 profile 不种 sys_config，所以走的是代码默认值。</li>
 * </ol>
 *
 * <p>本类**自建字典与空间**（`data-test.sql` 不种 `repair_category` / `spatial_node`），
 * 与 `SpatialDictIntegrationTest` 同一套路。名称刻意带唯一后缀：同一 JVM 里别的测试类
 * 建过同名分类，不应影响这里的断言。
 *
 * <p><b>整个类 `@Transactional`</b>：本类会造分类/空间/工单，必须回滚。
 * 这一条是实测踩出来的——第一版用 `workOrderService.submitOrder(STUDENT_ID, …)` 给
 * student01 真造了一张单，而 `PersonalCenterIntegrationTest#testRepairSummaryBuckets`
 * 对 student01 的分桶是**精确计数**，于是那个类报 `processingCount expected 2 but was 4`。
 * 另外进度用例改用**直插工单行**（照 `PersonalCenterIntegrationTest` 的
 * `OTHER_STUDENT_ID = 99L` 惯例给一个非真实用户 id），顺带绕开提单的异步派单线程。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AssistantApiIntegrationTest {

    private static final long STUDENT_ID = 3L;
    /** 非真实用户 id：进度用例直插工单行用，避免碰任何别人在断言的账号 */
    private static final long PROGRESS_STUDENT_ID = 98L;
    private static final long OTHER_STUDENT_ID = 99L;
    private static final long WORKER_ID = 2L;
    private static final long ADMIN_ID = 1L;

    /**
     * 每个测试方法都建一套自己的字典。**后缀必须逐个测试递增**：同一个 JVM 里
     * 所有测试类共用一个 H2 库，若两个测试用同名词条，同分之下排序会按 id 升序
     * 挑到**先建的那个**，断言"就是本次建的 id"就会假失败（本轮实测踩到）。
     */
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private String unique;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private RepairCategoryService categoryService;

    @Autowired
    private SpatialNodeService spatialNodeService;

    @Autowired
    private SysConfigService configService;

    @Autowired
    private RepairOrderMapper orderMapper;

    private Long waterLeafId;
    private Long roomId;

    private String bearer(long userId, String username, String... roles) {
        return "Bearer " + jwtUtils.generateAccessToken(userId, username, List.of(roles));
    }

    @BeforeEach
    void setUpDictionary() {
        unique = "ZX9" + SEQUENCE.incrementAndGet();
        RepairCategory parent = new RepairCategory();
        parent.setName("水暖管道" + unique);
        parent.setLevel(1);
        parent.setSlaHours(24);
        Long parentId = categoryService.createCategory(parent).getId();

        RepairCategory water = new RepairCategory();
        water.setParentId(parentId);
        water.setName("水龙头漏水" + unique);
        water.setWorkerSkillCode("PLUMBER");
        water.setSlaHours(24);
        water.setKeywords("水龙头" + unique + ",滴水,关不紧,渗水");
        water.setSelfHelpTip("先关闭角阀或入户水阀|用盆桶接水|拍照留存现场");
        waterLeafId = categoryService.createCategory(water).getId();

        RepairCategory other = new RepairCategory();
        other.setParentId(parentId);
        other.setName("下水道堵塞" + unique);
        other.setWorkerSkillCode("PLUMBER");
        other.setSlaHours(12);
        other.setKeywords("下水道" + unique + ",堵塞,返水");
        categoryService.createCategory(other);

        SpatialNode room = new SpatialNode();
        room.setName("A-101" + unique);
        room.setLevelType(1);
        roomId = spatialNodeService.createNode(room).getId();
    }

    private String suggestBody(String description) {
        return "{\"description\":\"" + description + "\"}";
    }

    // ------------------------------------------------------------------
    // 推荐
    // ------------------------------------------------------------------

    @Test
    @DisplayName("报修人提交水暖描述 → 首候选是该细类，工种/SLA/命中词/路径都对得上")
    void suggestFindsLeafByKeyword() throws Exception {
        mockMvc.perform(post("/api/v1/assistant/suggest")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(suggestBody("卫生间水龙头" + unique + "一直滴水，关不紧")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.engine").value("RULE"))
                .andExpect(jsonPath("$.data.degradedReason").doesNotExist())
                .andExpect(jsonPath("$.data.noMatch").value(false))
                .andExpect(jsonPath("$.data.candidates[0].categoryName").value("水龙头漏水" + unique))
                .andExpect(jsonPath("$.data.candidates[0].categoryId").value(waterLeafId))
                .andExpect(jsonPath("$.data.candidates[0].skillCode").value("PLUMBER"))
                .andExpect(jsonPath("$.data.candidates[0].slaHours").value(24))
                .andExpect(jsonPath("$.data.candidates[0].confidence").value("HIGH"))
                .andExpect(jsonPath("$.data.candidates[0].categoryPath").value("水暖管道" + unique + " / 水龙头漏水" + unique))
                .andExpect(jsonPath("$.data.candidates[0].matchedKeywords", not(empty())))
                // 首候选的自助排查步骤按 | 拆成数组
                .andExpect(jsonPath("$.data.selfHelpTips", hasSize(3)))
                .andExpect(jsonPath("$.data.selfHelpTips[0]").value("先关闭角阀或入户水阀"));
    }

    @Test
    @DisplayName("描述命中大类名时也给出候选（最弱信号，置信度 LOW）")
    void suggestFallsBackToParentNameHit() throws Exception {
        mockMvc.perform(post("/api/v1/assistant/suggest")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(suggestBody("水暖管道" + unique + "这块有点问题")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.noMatch").value(false))
                .andExpect(jsonPath("$.data.candidates", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$.data.candidates[0].confidence").value("LOW"));
    }

    @Test
    @DisplayName("完全对不上时不硬猜：noMatch=true、候选为空、回退给出大类")
    void suggestReturnsNoMatchWithFallback() throws Exception {
        mockMvc.perform(post("/api/v1/assistant/suggest")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(suggestBody("今天天气不错，想去打球，顺便问问图书馆几点关门")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.noMatch").value(true))
                .andExpect(jsonPath("$.data.candidates", empty()))
                .andExpect(jsonPath("$.data.fallbackCategories", not(empty())))
                .andExpect(jsonPath("$.data.fallbackCategories", hasSize(lessThanOrEqualTo(5))))
                .andExpect(jsonPath("$.data.fallbackCategories[0].name").isNotEmpty());
    }

    @Test
    @DisplayName("停用的细类不参与推荐；同关键词的启用细类照常命中")
    void stoppedCategoryIsExcluded() throws Exception {
        RepairCategory stopped = new RepairCategory();
        stopped.setParentId(null);
        stopped.setName("停用细类" + unique);
        Long parentId = categoryService.createCategory(stopped).getId();

        RepairCategory leaf = new RepairCategory();
        leaf.setParentId(parentId);
        leaf.setName("停用叶子" + unique);
        leaf.setWorkerSkillCode("PLUMBER");
        leaf.setSlaHours(24);
        leaf.setKeywords("停用专用词" + unique);
        RepairCategory created = categoryService.createCategory(leaf);

        // 停用前能命中
        mockMvc.perform(post("/api/v1/assistant/suggest")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(suggestBody("这里有停用专用词" + unique)))
                .andExpect(jsonPath("$.data.noMatch").value(false));

        created.setStatus(0);
        categoryService.updateCategory(created);

        mockMvc.perform(post("/api/v1/assistant/suggest")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(suggestBody("这里有停用专用词" + unique)))
                .andExpect(jsonPath("$.data.noMatch").value(true))
                .andExpect(jsonPath("$.data.candidates", empty()));
    }

    @Test
    @DisplayName("鉴权按职能分档：维修作业与调度管理调推荐一律 403")
    void suggestIsReporterOnly() throws Exception {
        mockMvc.perform(post("/api/v1/assistant/suggest")
                        .header("Authorization", bearer(WORKER_ID, "worker01", "WORKER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(suggestBody("水龙头" + unique + "滴水")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/assistant/suggest")
                        .header("Authorization", bearer(ADMIN_ID, "admin", "PLATFORM_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(suggestBody("水龙头" + unique + "滴水")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("描述长度不在 2~300 之间 → HTTP 400 且 code=400（参数校验，不是 500）")
    void suggestValidatesDescriptionLength() throws Exception {
        mockMvc.perform(post("/api/v1/assistant/suggest")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(suggestBody("水")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));

        mockMvc.perform(post("/api/v1/assistant/suggest")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(suggestBody("漏".repeat(301))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    // ------------------------------------------------------------------
    // 状态 / 制度问答
    // ------------------------------------------------------------------

    @Test
    @DisplayName("状态接口对全角色开放，默认是纯规则引擎（大模型未配置）")
    void statusIsOpenToAllFunctionsWithoutLlm() throws Exception {
        mockMvc.perform(get("/api/v1/assistant/status")
                        .header("Authorization", bearer(WORKER_ID, "worker01", "WORKER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.llmReady").value(false))
                .andExpect(jsonPath("$.data.engine").value("RULE"));

        mockMvc.perform(get("/api/v1/assistant/status")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.engine").value("RULE"));
    }

    @Test
    @DisplayName("总开关关掉后：状态报 enabled=false，推荐返回空结果而不是报错")
    void killSwitchDisablesAssistant() throws Exception {
        try {
            configService.updateConfig("assistant_enabled", "0");

            mockMvc.perform(get("/api/v1/assistant/status")
                            .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.enabled").value(false))
                    .andExpect(jsonPath("$.data.engine").value("RULE"));

            mockMvc.perform(post("/api/v1/assistant/suggest")
                            .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(suggestBody("水龙头" + unique + "滴水")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.noMatch").value(true))
                    .andExpect(jsonPath("$.data.candidates", empty()));
        } finally {
            // 必须还原：H2 是跨测试类共享的，留着 0 会让后面的类行为变化
            configService.updateConfig("assistant_enabled", "1");
        }
    }

    @Test
    @DisplayName("制度问答按职能裁剪：报修人 / 维修作业 / 调度·平台 三套各不相同，且互不含对方的专属问题")
    void faqIsScopedByFunction() throws Exception {
        // 报修人：有报修人专属题，没有师傅专属题
        mockMvc.perform(get("/api/v1/assistant/faq")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(10))) // 通用 2 + 报修人 8
                .andExpect(jsonPath("$.data[?(@.question=='报修后多久会有人上门？')]", hasSize(1)))
                .andExpect(jsonPath("$.data[?(@.question=='收到派单后我该做什么？')]", hasSize(0)))
                .andExpect(jsonPath("$.data[?(@.question=='自动派单什么时候会失败？')]", hasSize(0)));

        // 维修作业：有师傅专属题，没有报修人专属题（"要上门的正是他自己"）
        mockMvc.perform(get("/api/v1/assistant/faq")
                        .header("Authorization", bearer(WORKER_ID, "worker01", "WORKER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(10))) // 通用 2 + 维修作业 8
                .andExpect(jsonPath("$.data[?(@.question=='收到派单后我该做什么？')]", hasSize(1)))
                .andExpect(jsonPath("$.data[?(@.question=='报修后多久会有人上门？')]", hasSize(0)))
                .andExpect(jsonPath("$.data[?(@.question=='我在哪里查看我的工单？')]", hasSize(0)))
                .andExpect(jsonPath("$.data[?(@.question=='自动派单什么时候会失败？')]", hasSize(0)));

        // 调度 / 平台管理：有管理侧专属题，没有报修人与师傅的专属题
        mockMvc.perform(get("/api/v1/assistant/faq")
                        .header("Authorization", bearer(ADMIN_ID, "admin", "PLATFORM_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(10))) // 通用 2 + 调度·平台 8
                .andExpect(jsonPath("$.data[?(@.question=='自动派单什么时候会失败？')]", hasSize(1)))
                .andExpect(jsonPath("$.data[?(@.question=='报修后多久会有人上门？')]", hasSize(0)))
                .andExpect(jsonPath("$.data[?(@.question=='收到派单后我该做什么？')]", hasSize(0)));
    }

    @Test
    @DisplayName("多职能账号的制度问答取并集：worker02（WORKER+REPORTER）两套专属题都在，且不重复")
    void faqIsUnionForMultiFunctionAccount() throws Exception {
        mockMvc.perform(get("/api/v1/assistant/faq")
                        .header("Authorization", bearer(WORKER_ID, "worker02", "WORKER", "REPORTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(18))) // 通用 2 + 报修人 8 + 维修作业 8
                .andExpect(jsonPath("$.data[?(@.question=='报修后多久会有人上门？')]", hasSize(1)))
                .andExpect(jsonPath("$.data[?(@.question=='收到派单后我该做什么？')]", hasSize(1)))
                .andExpect(jsonPath("$.data[?(@.question=='自动派单什么时候会失败？')]", hasSize(0)));
    }

    @Test
    @DisplayName("制度问答：时效数值取自系统参数（测试 profile 无种子时用默认 72/2/24）")
    void faqUsesConfigValues() throws Exception {
        // 按问题文本定位而不是按下标——按下标的断言在"分组增删"时会静默测错那一条
        mockMvc.perform(get("/api/v1/assistant/faq")
                        .header("Authorization", bearer(STUDENT_ID, "student01", "REPORTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].question").isNotEmpty())
                // 超时自动核销的 72 小时、返工阈值 2 星、SLA 预警 24 小时
                .andExpect(jsonPath("$.data[?(@.question=='提交后我一直不验收会怎样？')].answer",
                        hasItem(containsString("72"))))
                .andExpect(jsonPath("$.data[?(@.question=='评价打几分会被要求返工？')].answer",
                        hasItem(containsString("2"))))
                .andExpect(jsonPath("$.data[?(@.question=='报修后多久会有人上门？')].answer",
                        hasItem(containsString("24"))));
    }

    // ------------------------------------------------------------------
    // 我的工单进度
    // ------------------------------------------------------------------

    @Test
    @DisplayName("进度速查只回本人的单：换个账号就是空的（无按 id 直读的越权面）")
    void myProgressIsScopedToCaller() throws Exception {
        String orderNo = insertOrder(PROGRESS_STUDENT_ID, "PENDING_ACCEPTANCE");

        mockMvc.perform(get("/api/v1/assistant/my-progress")
                        .header("Authorization", bearer(PROGRESS_STUDENT_ID, "progress-student", "REPORTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.orderNo").value(orderNo))
                .andExpect(jsonPath("$.data.status").value("PENDING_ACCEPTANCE"))
                .andExpect(jsonPath("$.data.statusLabel").value("待验收"))
                // 「下一步」必须来自状态样板，不能是空串
                .andExpect(jsonPath("$.data.nextStep").value("师傅已完工，请到工单详情验收并评价"))
                .andExpect(jsonPath("$.data.spaceFullPath").isNotEmpty())
                .andExpect(jsonPath("$.data.categoryName").value("水龙头漏水" + unique));

        // 别人看是空的（同一个接口，只按调用者 id 取数）
        mockMvc.perform(get("/api/v1/assistant/my-progress")
                        .header("Authorization", bearer(OTHER_STUDENT_ID, "nobody", "REPORTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("最近一张单按创建时间倒序：后提交的那张才是「我的工单进度」")
    void myProgressReturnsLatestOrder() throws Exception {
        // 时间戳**必须显式错开**：`getMyOrders` 只按 created_at 倒序，两次连插落在同一毫秒时
        // 顺序是未定义的（第一版就这么假失败过）。这是产品既有排序口径，不是助手引入的。
        insertOrder(PROGRESS_STUDENT_ID, "COMPLETED", LocalDateTime.now().minusMinutes(5));
        String latest = insertOrder(PROGRESS_STUDENT_ID, "IN_PROGRESS", LocalDateTime.now());

        mockMvc.perform(get("/api/v1/assistant/my-progress")
                        .header("Authorization", bearer(PROGRESS_STUDENT_ID, "progress-student", "REPORTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderNo").value(latest))
                .andExpect(jsonPath("$.data.nextStep").value("师傅正在处理中，请保持电话畅通"));
    }

    /** 直插工单行（照 `PersonalCenterIntegrationTest` 的写法，避开提单的异步派单线程） */
    private String insertOrder(long studentUserId, String status) {
        return insertOrder(studentUserId, status, LocalDateTime.now());
    }

    private String insertOrder(long studentUserId, String status, LocalDateTime createdAt) {
        RepairOrder order = new RepairOrder();
        String orderNo = "BXAS" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        order.setOrderNo(orderNo);
        order.setStudentUserId(studentUserId);
        order.setApplicantName("助手进度测试用户");
        order.setApplicantPhone("13800000098");
        order.setSpaceId(roomId);
        order.setSpaceFullPath("A-101" + unique);
        order.setCategoryId(waterLeafId);
        order.setCategoryFullName("水龙头漏水" + unique);
        order.setDescription("助手进度速查验证单");
        order.setAppointmentPeriod("上午08:30-11:30");
        order.setUrgencyLevel(1);
        order.setStatus(status);
        order.setIsArchived(0);
        order.setDeleted(0);
        order.setCreatedAt(createdAt);
        order.setUpdatedAt(createdAt);
        orderMapper.insert(order);
        return orderNo;
    }

    @Test
    @DisplayName("进度速查同样只对报修人开放")
    void myProgressIsReporterOnly() throws Exception {
        mockMvc.perform(get("/api/v1/assistant/my-progress")
                        .header("Authorization", bearer(WORKER_ID, "worker01", "WORKER")))
                .andExpect(status().isForbidden());
    }
}
