package com.campus.repair.review;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.repair.modules.notification.entity.SysNotification;
import com.campus.repair.modules.notification.mapper.SysNotificationMapper;
import com.campus.repair.modules.review.constant.ContentReviewCodes;
import com.campus.repair.modules.review.entity.SysAnnouncement;
import com.campus.repair.modules.review.entity.SysContentReview;
import com.campus.repair.modules.review.mapper.SysAnnouncementMapper;
import com.campus.repair.modules.review.mapper.SysContentReviewMapper;
import com.campus.repair.modules.user.entity.SysUser;
import com.campus.repair.modules.user.mapper.SysUserMapper;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 公告「先审后发」端到端（Phase 02）。
 *
 * <p>锁死四件事：
 * <ol>
 *   <li>任何未到 {@code PUBLISHED} 的状态都不产生 {@code sys_notification}（草稿/待审/驳回/通过未发布）；</li>
 *   <li>{@code submit} 落一条 {@code sys_content_review(ANNOUNCEMENT, PENDING)} 轨迹（轮次递增）；</li>
 *   <li>未通过就发布 → {@code 2008}；</li>
 *   <li>{@code approve + publish} 后扇出条数 = 正常账号数，且每条通知都带 {@code announcement_id}。</li>
 * </ol>
 * 整类 {@code @Transactional}：夹具随用例回滚，不污染共享 H2 里其它类的精确计数断言。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AnnouncementReviewIntegrationTest {

    private static final String TITLE = "关于东校区停水检修的通知";
    private static final String CONTENT = "本周六上午东校区停水检修，请各部门提前做好储水与告知工作。";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SysAnnouncementMapper announcementMapper;

    @Autowired
    private SysContentReviewMapper contentReviewMapper;

    @Autowired
    private SysNotificationMapper notificationMapper;

    @Autowired
    private SysUserMapper userMapper;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @MockBean
    private ValueOperations<String, String> valueOperations;

    @BeforeEach
    void setUp() {
        Mockito.when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    // ------------------------------------------------------------------
    // 夹具与断言辅助
    // ------------------------------------------------------------------

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

    /** 按字节解析：MockHttpServletResponse#getContentAsString() 默认 ISO-8859-1，会把中文读成乱码。 */
    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private long createDraft(String token, String title, String content) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/announcements")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", title, "content", content))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value(ContentReviewCodes.ANNOUNCEMENT_DRAFT))
                .andReturn();
        return readJson(result).path("data").path("id").asLong();
    }

    private JsonNode submit(String token, long id) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/announcements/" + id + "/submit")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        return readJson(result);
    }

    private JsonNode review(String token, long id, String decision, String reason) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("decision", decision);
        if (reason != null) {
            body.put("reason", reason);
        }
        MvcResult result = mockMvc.perform(post("/api/v1/announcements/" + id + "/review")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        return readJson(result);
    }

    private long notificationCount() {
        Long count = notificationMapper.selectCount(null);
        return count == null ? 0 : count;
    }

    private long activeUserCount() {
        Long count = userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getStatus, 1));
        return count == null ? 0 : count;
    }

    private List<SysContentReview> tracesOf(long announcementId) {
        return contentReviewMapper.selectList(new LambdaQueryWrapper<SysContentReview>()
                .eq(SysContentReview::getBizType, ContentReviewCodes.BIZ_ANNOUNCEMENT)
                .eq(SysContentReview::getBizId, announcementId)
                .orderByAsc(SysContentReview::getSubmitRound)
                .orderByAsc(SysContentReview::getId));
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    @Test
    @DisplayName("① 新建草稿不扇出：sys_notification 不增长，状态 DRAFT")
    void createDraftDoesNotFanOut() throws Exception {
        String token = loginAndGetToken("admin", "password");
        long before = notificationCount();

        long id = createDraft(token, TITLE, CONTENT);

        assertThat(notificationCount()).as("草稿不得产生站内信").isEqualTo(before);
        SysAnnouncement saved = announcementMapper.selectById(id);
        assertThat(saved.getStatus()).isEqualTo(ContentReviewCodes.ANNOUNCEMENT_DRAFT);
        assertThat(saved.getPublishedAt()).isNull();
        assertThat(tracesOf(id)).isEmpty();
    }

    @Test
    @DisplayName("② submit → PENDING_REVIEW，同时落一条 ANNOUNCEMENT PENDING 审核轨迹，仍不扇出")
    void submitMovesToPendingReviewAndRecordsTrace() throws Exception {
        String token = loginAndGetToken("admin", "password");
        long id = createDraft(token, TITLE, CONTENT);
        long before = notificationCount();

        JsonNode response = submit(token, id);

        assertThat(response.path("code").asInt()).isEqualTo(200);
        assertThat(response.path("data").path("status").asText())
                .isEqualTo(ContentReviewCodes.ANNOUNCEMENT_PENDING_REVIEW);
        List<SysContentReview> traces = tracesOf(id);
        assertThat(traces).hasSize(1);
        assertThat(traces.get(0).getOutcome()).isEqualTo(ContentReviewCodes.OUTCOME_PENDING);
        assertThat(traces.get(0).getSubmitRound()).isEqualTo(1);
        assertThat(traces.get(0).getBizNo()).isEqualTo(TITLE);
        assertThat(traces.get(0).getScreenResult()).isEqualTo(ContentReviewCodes.SCREEN_SUSPECTED);
        assertThat(notificationCount()).as("待审公告不得产生站内信").isEqualTo(before);
    }

    @Test
    @DisplayName("③ 未通过审核就发布 → 2008，且状态不变、不扇出")
    void publishBeforeApprovedRejected() throws Exception {
        String token = loginAndGetToken("admin", "password");
        long id = createDraft(token, TITLE, CONTENT);
        submit(token, id);
        long before = notificationCount();

        mockMvc.perform(post("/api/v1/announcements/" + id + "/publish")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2008));

        assertThat(announcementMapper.selectById(id).getStatus())
                .isEqualTo(ContentReviewCodes.ANNOUNCEMENT_PENDING_REVIEW);
        assertThat(notificationCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("④ approve + publish：扇出条数 = 正常账号数，每条通知带 announcement_id")
    void approveAndPublishFanOut() throws Exception {
        String token = loginAndGetToken("admin", "password");
        long id = createDraft(token, TITLE, CONTENT);
        submit(token, id);

        JsonNode approved = review(token, id, ContentReviewCodes.ANNOUNCEMENT_APPROVED, "复核无违规");
        assertThat(approved.path("code").asInt()).isEqualTo(200);
        assertThat(approved.path("data").path("status").asText())
                .isEqualTo(ContentReviewCodes.ANNOUNCEMENT_APPROVED);
        assertThat(tracesOf(id).get(0).getOutcome()).isEqualTo(ContentReviewCodes.OUTCOME_APPROVED);

        long before = notificationCount();
        long expected = activeUserCount();

        mockMvc.perform(post("/api/v1/announcements/" + id + "/publish")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value(ContentReviewCodes.ANNOUNCEMENT_PUBLISHED))
                .andExpect(jsonPath("$.data.publishedAt").exists());

        assertThat(notificationCount() - before)
                .as("扇出条数应等于正常账号数").isEqualTo(expected);

        List<SysNotification> fanout = notificationMapper.selectList(
                new LambdaQueryWrapper<SysNotification>().eq(SysNotification::getAnnouncementId, id));
        assertThat(fanout).hasSize((int) expected);
        assertThat(fanout).allSatisfy(notification -> {
            assertThat(notification.getAnnouncementId()).isEqualTo(id);
            assertThat(notification.getCategory()).isEqualTo(ContentReviewCodes.NOTIFICATION_CATEGORY_BROADCAST);
            assertThat(notification.getTitle()).isEqualTo(TITLE);
        });
    }

    @Test
    @DisplayName("⑤ reject 必填理由；驳回后不可发布、可编辑重提（轮次递增、仍不扇出）")
    void rejectThenEditAndResubmit() throws Exception {
        String token = loginAndGetToken("admin", "password");
        long id = createDraft(token, TITLE, CONTENT);
        submit(token, id);
        long before = notificationCount();

        // 驳回理由必填：不传理由 → 业务码 400（HTTP 200）
        JsonNode noReason = review(token, id, ContentReviewCodes.ANNOUNCEMENT_REJECTED, null);
        assertThat(noReason.path("code").asInt()).isEqualTo(400);

        JsonNode rejected = review(token, id, ContentReviewCodes.ANNOUNCEMENT_REJECTED, "正文缺少停水范围，请补充");
        assertThat(rejected.path("code").asInt()).isEqualTo(200);
        assertThat(rejected.path("data").path("status").asText())
                .isEqualTo(ContentReviewCodes.ANNOUNCEMENT_REJECTED);
        assertThat(rejected.path("data").path("reviewReason").asText()).contains("停水范围");

        // 驳回态不可发布
        mockMvc.perform(post("/api/v1/announcements/" + id + "/publish")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2008));

        // 修改稿：仅 DRAFT/REJECTED 可编辑
        mockMvc.perform(put("/api/v1/announcements/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", TITLE,
                                "content", CONTENT + "（补充：覆盖东校区全部宿舍楼）"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value(ContentReviewCodes.ANNOUNCEMENT_REJECTED));

        // 重提 → 第二轮轨迹
        JsonNode resubmitted = submit(token, id);
        assertThat(resubmitted.path("data").path("status").asText())
                .isEqualTo(ContentReviewCodes.ANNOUNCEMENT_PENDING_REVIEW);
        List<SysContentReview> traces = tracesOf(id);
        assertThat(traces).hasSize(2);
        assertThat(traces.get(0).getOutcome()).isEqualTo(ContentReviewCodes.OUTCOME_REJECTED);
        assertThat(traces.get(1).getOutcome()).isEqualTo(ContentReviewCodes.OUTCOME_PENDING);
        assertThat(traces.get(1).getSubmitRound()).isEqualTo(2);
        assertThat(notificationCount()).as("重提仍未发布，不得扇出").isEqualTo(before);
    }

    @Test
    @DisplayName("⑥ 旧 broadcast 端点：只建公告草稿、返回 announcementId、不扇出")
    void legacyBroadcastCreatesDraftOnly() throws Exception {
        String token = loginAndGetToken("admin", "password");
        long before = notificationCount();

        MvcResult result = mockMvc.perform(post("/api/v1/notifications/broadcast")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "title", "全校停水通知",
                                "content", "因管网检修，明日上午 9:00-11:00 全校暂停供水。"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.announcementId").isNumber())
                .andReturn();

        long announcementId = readJson(result).path("data").path("announcementId").asLong();
        SysAnnouncement draft = announcementMapper.selectById(announcementId);
        assertThat(draft).isNotNull();
        assertThat(draft.getStatus()).isEqualTo(ContentReviewCodes.ANNOUNCEMENT_DRAFT);
        assertThat(draft.getTitle()).isEqualTo("全校停水通知");
        assertThat(notificationCount()).as("旧端点兼容语义：只建草稿，不扇出").isEqualTo(before);
    }

    @Test
    @DisplayName("⑦ 统一复核队列：公告类审核行可在 /content-reviews 列表中看到并通过，联动公告状态")
    void contentReviewQueueApprovesAnnouncement() throws Exception {
        String token = loginAndGetToken("admin", "password");
        long id = createDraft(token, TITLE, CONTENT);
        submit(token, id);
        long reviewId = tracesOf(id).get(0).getId();

        // 队列列表：bizType=ANNOUNCEMENT 时联表带出作者名（公告没有空间路径/分类）
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/content-reviews")
                        .header("Authorization", "Bearer " + token)
                        .param("bizType", "ANNOUNCEMENT")
                        .param("outcome", "PENDING")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.records[?(@.id == " + reviewId + ")].applicantName")
                        .value(org.hamcrest.Matchers.hasItem("管理员")))
                .andExpect(jsonPath("$.data.records[?(@.id == " + reviewId + ")].bizNo")
                        .value(org.hamcrest.Matchers.hasItem(TITLE)));

        // 详情带历史轮次
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/content-reviews/" + reviewId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.history.length()").value(1));

        // 统一入口通过 → 公告状态联动为 APPROVED
        mockMvc.perform(post("/api/v1/content-reviews/" + reviewId + "/approve")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "复核无违规"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.outcome").value(ContentReviewCodes.OUTCOME_APPROVED));

        assertThat(announcementMapper.selectById(id).getStatus())
                .isEqualTo(ContentReviewCodes.ANNOUNCEMENT_APPROVED);
        assertThat(tracesOf(id).get(0).getOutcome()).isEqualTo(ContentReviewCodes.OUTCOME_APPROVED);

        // 二次决策 → 2007（一次性决策）
        mockMvc.perform(post("/api/v1/content-reviews/" + reviewId + "/approve")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reason", "重复点击"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(2007));
    }
}
