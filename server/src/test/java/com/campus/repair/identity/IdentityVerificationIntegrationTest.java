package com.campus.repair.identity;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.repair.framework.util.JwtUtils;
import com.campus.repair.modules.auth.dto.RegisterRequest;
import com.campus.repair.modules.identity.constant.IdentityReviewCodes;
import com.campus.repair.modules.identity.entity.IdentityReview;
import com.campus.repair.modules.identity.entity.IdentityRoster;
import com.campus.repair.modules.identity.mapper.IdentityReviewMapper;
import com.campus.repair.modules.identity.mapper.IdentityRosterMapper;
import com.campus.repair.modules.notification.entity.SysNotification;
import com.campus.repair.modules.notification.mapper.SysNotificationMapper;
import com.campus.repair.modules.user.entity.SysUser;
import com.campus.repair.modules.user.mapper.SysUserMapper;
import com.campus.repair.modules.workorder.dto.DuplicateCheckRequest;
import com.campus.repair.modules.workorder.dto.WorkOrderSubmitRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 校内身份核验端到端（Phase 01）。
 *
 * <p>覆盖判定表四条分支、三态账号、核验端点、一次性决策、驳回后重新提交，以及
 * 「服务层再查一次库」的闸门（令牌只是 2 小时快照）。所有用例 {@code @Transactional}：
 * 夹具随用例回滚，不做跨类数据清理。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class IdentityVerificationIntegrationTest {

    /** 一个必然不存在的空间 id：提单校验里排在身份闸门之后，用来证明「闸门已放行」。 */
    private static final long NON_EXISTENT_SPACE_ID = 999_999L;
    private static final long NON_EXISTENT_CATEGORY_ID = 999_999L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private IdentityRosterMapper rosterMapper;

    @Autowired
    private IdentityReviewMapper reviewMapper;

    @Autowired
    private SysNotificationMapper notificationMapper;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @MockBean
    private ValueOperations<String, String> valueOperations;

    @BeforeEach
    void setUp() {
        Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    private static String uniqueUsername(String prefix) {
        return prefix + "_" + System.nanoTime();
    }

    private static String uniquePhone() {
        return "139" + String.format("%08d", Math.abs(System.nanoTime()) % 100_000_000);
    }

    private IdentityRoster insertRoster(String username, String realName, String userType, int status) {
        IdentityRoster roster = new IdentityRoster();
        roster.setUsername(username);
        roster.setRealName(realName);
        roster.setUserType(userType);
        roster.setStatus(status);
        roster.setImportBatch("test");
        rosterMapper.insert(roster);
        return roster;
    }

    private JsonNode register(String username, String realName, String phone, String branch) throws Exception {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setRealName(realName);
        request.setPhone(phone);
        request.setPassword("Password@2026");
        request.setUserType(branch);

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        return readJson(result);
    }

    /** 按**字节**解析响应体：MockHttpServletResponse#getContentAsString() 默认 ISO-8859-1，会把中文读成乱码。 */
    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result);
    }

    private SysUser findUser(String username) {
        List<SysUser> users = userMapper.selectList(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username)
                .orderByAsc(SysUser::getId));
        return users.isEmpty() ? null : users.get(0);
    }

    private IdentityReview latestReview(Long userId) {
        List<IdentityReview> reviews = reviewMapper.selectList(new LambdaQueryWrapper<IdentityReview>()
                .eq(IdentityReview::getUserId, userId)
                .orderByDesc(IdentityReview::getId));
        return reviews.isEmpty() ? null : reviews.get(0);
    }

    private int reviewCount(Long userId) {
        return reviewMapper.selectList(new LambdaQueryWrapper<IdentityReview>()
                .eq(IdentityReview::getUserId, userId)).size();
    }

    private String adminToken() {
        return jwtUtils.generateAccessToken(1L, "admin", List.of("PLATFORM_ADMIN"));
    }

    private String reporterToken(Long userId, String username) {
        return jwtUtils.generateAccessToken(userId, username, List.of("REPORTER"));
    }

    private WorkOrderSubmitRequest submitBody() {
        WorkOrderSubmitRequest request = new WorkOrderSubmitRequest();
        request.setSpaceId(NON_EXISTENT_SPACE_ID);
        request.setCategoryId(NON_EXISTENT_CATEGORY_ID);
        request.setDescription("身份核验闸门探针：这是一条不会落库的提单请求");
        request.setAppointmentPeriod("上午08:30-11:30");
        return request;
    }

    // ------------------------------------------------------------------
    // ① 名单命中 → 自动通过
    // ------------------------------------------------------------------

    @Test
    @DisplayName("名单命中且姓名一致：注册即 status=1 / MATCHED，并落 SYSTEM 自动审核行")
    void testRosterMatchedRegisterAutoApproved() throws Exception {
        String username = uniqueUsername("id_match");
        insertRoster(username, "张三", "STUDENT", 1);

        JsonNode body = register(username, " 张 三 ", uniquePhone(), "STUDENT");
        JsonNode data = body.path("data");

        assertThat(body.path("code").asInt()).isEqualTo(200);
        assertThat(data.path("status").asInt()).isEqualTo(1);
        assertThat(data.path("identityVerified").asBoolean()).isTrue();
        assertThat(data.path("matchResult").asText()).isEqualTo(IdentityReviewCodes.MATCHED);
        assertThat(data.path("pendingReason").isNull()).isTrue();

        SysUser user = findUser(username);
        assertThat(user).isNotNull();
        assertThat(user.getStatus()).isEqualTo(1);
        assertThat(user.getIdentityVerified()).isEqualTo(1);
        assertThat(user.getUserType()).isEqualTo("STUDENT");

        IdentityReview review = latestReview(user.getId());
        assertThat(review).isNotNull();
        assertThat(review.getOutcome()).isEqualTo(IdentityReviewCodes.OUTCOME_APPROVED);
        assertThat(review.getApplyType()).isEqualTo(IdentityReviewCodes.APPLY_REGISTER);
        assertThat(review.getMatchResult()).isEqualTo(IdentityReviewCodes.MATCHED);
        assertThat(review.getReviewerId()).isEqualTo(IdentityReviewCodes.SYSTEM_REVIEWER_ID);
        assertThat(review.getReviewerName()).isEqualTo(IdentityReviewCodes.SYSTEM_REVIEWER_NAME);

        // 登录响应必须带上核验状态与账号状态（前端据此决定落地页）
        JsonNode loginBody = login(username, "Password@2026");
        assertThat(loginBody.path("data").path("identityVerified").asBoolean()).isTrue();
        assertThat(loginBody.path("data").path("accountStatus").asInt()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // ② 名单外 → 待审核，能登录但不能提单
    // ------------------------------------------------------------------

    @Test
    @DisplayName("名单外注册：status=2 / NOT_IN_ROSTER，可登录看资料，提单与防重一律 1010")
    void testOutsiderRegisterPendingAndCannotSubmit() throws Exception {
        String username = uniqueUsername("id_outsider");
        String phone = uniquePhone();

        JsonNode data = register(username, "名单外同学", phone, "STUDENT").path("data");
        assertThat(data.path("status").asInt()).isEqualTo(2);
        assertThat(data.path("identityVerified").asBoolean()).isFalse();
        assertThat(data.path("matchResult").asText()).isEqualTo(IdentityReviewCodes.NOT_IN_ROSTER);
        assertThat(data.path("pendingReason").asText()).contains("名单");

        SysUser user = findUser(username);
        assertThat(user.getStatus()).isEqualTo(2);
        assertThat(user.getIdentityVerified()).isEqualTo(0);

        // 登录放行（待审核允许登录，否则用户看不到为什么不能提单）
        JsonNode loginBody = login(username, "Password@2026");
        assertThat(loginBody.path("code").asInt()).isEqualTo(200);
        assertThat(loginBody.path("data").path("accountStatus").asInt()).isEqualTo(2);
        assertThat(loginBody.path("data").path("identityVerified").asBoolean()).isFalse();
        String token = loginBody.path("data").path("accessToken").asText();

        // 放行：本人资料
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.identityVerified").value(false))
                .andExpect(jsonPath("$.data.status").value(2));

        // 拦截：提单 / 防重（HTTP 200 + 业务码 1010，不是 401/403）
        mockMvc.perform(post("/api/v1/work-orders/submit")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitBody())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1010));

        DuplicateCheckRequest duplicate = new DuplicateCheckRequest();
        duplicate.setSpaceId(NON_EXISTENT_SPACE_ID);
        duplicate.setCategoryId(NON_EXISTENT_CATEGORY_ID);
        mockMvc.perform(post("/api/v1/work-orders/check-duplicate")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1010));
    }

    // ------------------------------------------------------------------
    // ③ 姓名不符 → 待审核，审核页可见
    // ------------------------------------------------------------------

    @Test
    @DisplayName("姓名与名册不符：MISMATCHED 待审核，管理员审核页能按学号检索到")
    void testNameMismatchVisibleInReviewQueue() throws Exception {
        String username = uniqueUsername("id_mismatch");
        insertRoster(username, "李四", "STUDENT", 1);

        JsonNode data = register(username, "李四改", uniquePhone(), "STUDENT").path("data");
        assertThat(data.path("status").asInt()).isEqualTo(2);
        assertThat(data.path("matchResult").asText()).isEqualTo(IdentityReviewCodes.MISMATCHED);

        mockMvc.perform(get("/api/v1/identity/reviews")
                        .header("Authorization", "Bearer " + adminToken())
                        .param("outcome", "PENDING")
                        .param("keyword", username))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].username").value(username))
                .andExpect(jsonPath("$.data.records[0].realName").value("李四改"))
                .andExpect(jsonPath("$.data.records[0].userType").value("STUDENT"))
                .andExpect(jsonPath("$.data.records[0].matchResult")
                        .value(IdentityReviewCodes.MISMATCHED))
                .andExpect(jsonPath("$.data.records[0].outcome")
                        .value(IdentityReviewCodes.OUTCOME_PENDING));
    }

    // ------------------------------------------------------------------
    // ④ 审核通过 → 解锁提单 + 通知；⑤ 二次决策 → 1013
    // ------------------------------------------------------------------

    @Test
    @DisplayName("审核通过：账号转正常、发通知、提单闸门放行；二次审核返回 1013")
    void testApproveUnlocksSubmitAndNotifies() throws Exception {
        String username = uniqueUsername("id_approve");
        register(username, "待审同学", uniquePhone(), "STUDENT");
        SysUser user = findUser(username);
        IdentityReview review = latestReview(user.getId());
        String token = adminToken();

        mockMvc.perform(post("/api/v1/identity/reviews/" + review.getId() + "/approve")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        SysUser approved = userMapper.selectById(user.getId());
        assertThat(approved.getStatus()).isEqualTo(1);
        assertThat(approved.getIdentityVerified()).isEqualTo(1);

        IdentityReview decided = reviewMapper.selectById(review.getId());
        assertThat(decided.getOutcome()).isEqualTo(IdentityReviewCodes.OUTCOME_APPROVED);
        assertThat(decided.getReviewerId()).isEqualTo(1L);
        assertThat(decided.getReviewerName()).isEqualTo("管理员");
        assertThat(decided.getReviewedAt()).isNotNull();

        List<SysNotification> notifications = notificationMapper.selectList(
                new LambdaQueryWrapper<SysNotification>().eq(SysNotification::getUserId, user.getId()));
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).getCategory()).isEqualTo(IdentityReviewCodes.NOTIFICATION_CATEGORY);

        // 提单闸门已放行：身份判定在空间/分类校验之前，因此「空间不存在」的 400 才是放行证据
        String freshToken = login(username, "Password@2026").path("data").path("accessToken").asText();
        mockMvc.perform(post("/api/v1/work-orders/submit")
                        .header("Authorization", "Bearer " + freshToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitBody())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(400));

        // 一次性决策：已处理的单不能再审
        mockMvc.perform(post("/api/v1/identity/reviews/" + review.getId() + "/approve")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1013));
    }

    // ------------------------------------------------------------------
    // ⑥ 驳回 → 不能登录；本人能看到理由
    // ------------------------------------------------------------------

    @Test
    @DisplayName("驳回：账号停用（登录 1001），本人 /reviews/me/latest 能看到驳回理由")
    void testRejectBlocksLoginAndLatestReviewCarriesReason() throws Exception {
        String username = uniqueUsername("id_reject");
        insertRoster(username, "王五", "STUDENT", 1);
        register(username, "王五五", uniquePhone(), "STUDENT");

        SysUser user = findUser(username);
        IdentityReview review = latestReview(user.getId());

        mockMvc.perform(post("/api/v1/identity/reviews/" + review.getId() + "/reject")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("reason", "姓名与名册不符，请携带学生证到信息中心"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        SysUser rejected = userMapper.selectById(user.getId());
        assertThat(rejected.getStatus()).isEqualTo(0);
        assertThat(rejected.getIdentityVerified()).isEqualTo(0);

        // 停用账号不能登录（沿用既有 1001 语义）
        assertThat(login(username, "Password@2026").path("code").asInt()).isEqualTo(1001);

        // 待核验页取理由：该端点对「任何登录用户」开放，这里用直签令牌模拟用户仍持有会话
        mockMvc.perform(get("/api/v1/identity/reviews/me/latest")
                        .header("Authorization", "Bearer " + reporterToken(user.getId(), username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.outcome").value(IdentityReviewCodes.OUTCOME_REJECTED))
                .andExpect(jsonPath("$.data.reason").value("姓名与名册不符，请携带学生证到信息中心"));
    }

    // ------------------------------------------------------------------
    // ⑦ 重新提交 → 复用同一账号，新增 REAPPLY 行
    // ------------------------------------------------------------------

    @Test
    @DisplayName("被驳回后用同一学号重新提交：复用 user_id、新增 REAPPLY 审核行，核验后重复注册才 1006")
    void testReapplyReusesSameUserAndWritesReapplyRow() throws Exception {
        String username = uniqueUsername("id_reapply");
        String phone = uniquePhone();
        insertRoster(username, "赵六", "STUDENT", 1);

        // 第一次：姓名不符 → 待审核 → 驳回
        register(username, "赵六六", phone, "STUDENT");
        SysUser user = findUser(username);
        Long userId = user.getId();
        Long pendingReviewId = latestReview(userId).getId();
        mockMvc.perform(post("/api/v1/identity/reviews/" + pendingReviewId + "/reject")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "请用真实姓名"))))
                .andExpect(jsonPath("$.code").value(200));

        // 第二次：同一学号 + 正确姓名 → 自动通过，不新建用户、不报 1006
        JsonNode data = register(username, "赵六", phone, "STUDENT").path("data");
        assertThat(data.path("status").asInt()).isEqualTo(1);
        assertThat(data.path("identityVerified").asBoolean()).isTrue();

        SysUser reapplied = findUser(username);
        assertThat(reapplied.getId()).isEqualTo(userId);
        assertThat(reapplied.getStatus()).isEqualTo(1);
        assertThat(reapplied.getIdentityVerified()).isEqualTo(1);

        List<IdentityReview> reviews = reviewMapper.selectList(new LambdaQueryWrapper<IdentityReview>()
                .eq(IdentityReview::getUserId, userId)
                .orderByAsc(IdentityReview::getId));
        assertThat(reviews).hasSize(2);
        assertThat(reviews.get(0).getApplyType()).isEqualTo(IdentityReviewCodes.APPLY_REGISTER);
        assertThat(reviews.get(1).getApplyType()).isEqualTo(IdentityReviewCodes.APPLY_REAPPLY);
        assertThat(reviews.get(1).getOutcome()).isEqualTo(IdentityReviewCodes.OUTCOME_APPROVED);

        // 已核验账号再用同一学号注册 → 1006（既有语义不变）
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registerRequest(username, "赵六", phone))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1006));
    }

    // ------------------------------------------------------------------
    // ⑧ 名单失效 → 1011
    // ------------------------------------------------------------------

    @Test
    @DisplayName("名册已失效（离校/离职）→ 拒绝注册 1011，且不落库")
    void testInactiveRosterRejectsRegistration() throws Exception {
        String username = uniqueUsername("id_inactive");
        insertRoster(username, "钱七", "STUDENT", 0);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                registerRequest(username, "钱七", uniquePhone()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1011));

        assertThat(findUser(username)).isNull();
    }

    // ------------------------------------------------------------------
    // ⑨ 越权：非平台管理员不得调核验端点
    // ------------------------------------------------------------------

    @Test
    @DisplayName("待审核账号不会收到全校广播（fan-out 只发 status=1，Phase 01 §5 口径）")
    void testPendingAccountExcludedFromBroadcast() throws Exception {
        String username = uniqueUsername("id_broadcast");
        register(username, "待审广播", uniquePhone(), "STUDENT");
        SysUser user = findUser(username);

        mockMvc.perform(post("/api/v1/notifications/broadcast")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("title", "广播探针", "content", "待核验账号不应收到这条广播"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        assertThat(notificationMapper.selectList(new LambdaQueryWrapper<SysNotification>()
                .eq(SysNotification::getUserId, user.getId())))
                .as("待审核账号天然收不到公告（getAllActiveUsers 只取 status=1）")
                .isEmpty();
    }
    @Test
    @DisplayName("REPORTER / DISPATCHER 调核验端点 → 403")
    void testNonAdminCannotCallIdentityEndpoints() throws Exception {
        String reporter = jwtUtils.generateAccessToken(3L, "student01", List.of("REPORTER"));
        String dispatcher = jwtUtils.generateAccessToken(2L, "worker01", List.of("DISPATCHER"));

        for (String token : List.of(reporter, dispatcher)) {
            mockMvc.perform(get("/api/v1/identity/reviews").header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
            mockMvc.perform(get("/api/v1/identity/roster").header("Authorization", "Bearer " + token))
                    .andExpect(status().isForbidden());
        }
    }

    // ------------------------------------------------------------------
    // ⑩ 令牌只是快照：库里已核验状态才是提单权威判据
    // ------------------------------------------------------------------

    @Test
    @DisplayName("旧令牌/伪造核验声明：库里仍待审核时服务层依旧拦成 1010")
    void testServiceLayerGateBeatsStaleToken() throws Exception {
        String username = uniqueUsername("id_stale");
        register(username, "存量待审", uniquePhone(), "STUDENT");
        SysUser user = findUser(username);

        // 模拟「签发时还是正常账号、之后被打回待审核」的旧令牌：verified=true 但库里 status=2
        String staleToken = jwtUtils.generateAccessToken(user.getId(), username, List.of("REPORTER"), false, true);
        mockMvc.perform(post("/api/v1/work-orders/submit")
                        .header("Authorization", "Bearer " + staleToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submitBody())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1010));

        // 防重入口同样要查库（该端点签名里没有调用者，闸门在服务层从安全上下文取主体）
        DuplicateCheckRequest duplicate = new DuplicateCheckRequest();
        duplicate.setSpaceId(NON_EXISTENT_SPACE_ID);
        duplicate.setCategoryId(NON_EXISTENT_CATEGORY_ID);
        mockMvc.perform(post("/api/v1/work-orders/check-duplicate")
                        .header("Authorization", "Bearer " + staleToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(duplicate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1010));
    }

    @Test
    @DisplayName("无 verified claim 的旧令牌按「已核验」解析（升级不打扰在线用户）")
    void testLegacyTokenWithoutClaimDefaultsToVerified() {
        String legacyToken = jwtUtils.generateAccessToken(3L, "student01", List.of("REPORTER"));
        assertThat(jwtUtils.isIdentityVerified(legacyToken)).isTrue();

        String pendingToken = jwtUtils.generateAccessToken(3L, "student01", List.of("REPORTER"), false, false);
        assertThat(jwtUtils.isIdentityVerified(pendingToken)).isFalse();
    }

    private RegisterRequest registerRequest(String username, String realName, String phone) {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setRealName(realName);
        request.setPhone(phone);
        request.setPassword("Password@2026");
        request.setUserType("STUDENT");
        return request;
    }
}
