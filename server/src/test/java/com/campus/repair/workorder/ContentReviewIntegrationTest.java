package com.campus.repair.workorder;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.repair.common.exception.BusinessException;
import com.campus.repair.common.exception.ErrorCode;
import com.campus.repair.framework.util.JwtUtils;
import com.campus.repair.modules.notification.entity.SysNotification;
import com.campus.repair.modules.notification.mapper.SysNotificationMapper;
import com.campus.repair.modules.review.service.ContentReviewRecordService;
import com.campus.repair.modules.spatial.entity.RepairCategory;
import com.campus.repair.modules.spatial.entity.SpatialNode;
import com.campus.repair.modules.spatial.service.RepairCategoryService;
import com.campus.repair.modules.spatial.service.SpatialNodeService;
import com.campus.repair.modules.user.entity.SysUser;
import com.campus.repair.modules.user.entity.WorkerProfile;
import com.campus.repair.modules.user.mapper.SysUserMapper;
import com.campus.repair.modules.user.mapper.WorkerProfileMapper;
import com.campus.repair.modules.workorder.dto.DispatchPoolQueryRequest;
import com.campus.repair.modules.workorder.dto.WorkOrderManualDispatchRequest;
import com.campus.repair.modules.workorder.dto.WorkOrderReporterCancelRequest;
import com.campus.repair.modules.workorder.dto.WorkOrderResubmitRequest;
import com.campus.repair.modules.workorder.dto.WorkOrderSubmitRequest;
import com.campus.repair.modules.workorder.entity.RepairOrder;
import com.campus.repair.modules.workorder.entity.RepairOrderLog;
import com.campus.repair.modules.workorder.mapper.ContentReviewTraceMapper;
import com.campus.repair.modules.workorder.mapper.RepairOrderMapper;
import com.campus.repair.modules.workorder.service.WorkOrderLogService;
import com.campus.repair.modules.workorder.service.WorkOrderService;
import com.campus.repair.modules.workorder.vo.ContentReviewTraceVO;
import com.campus.repair.modules.workorder.vo.DispatchPoolResultVO;
import com.campus.repair.modules.workorder.vo.WorkOrderSubmitResultVO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 内容审核闸门（Phase 02 · 工单侧）端到端回归。
 *
 * <p>覆盖的判定链：
 * <ol>
 *   <li>命中初筛的提单**不进派单池**、**不能被人工指派**（2004）、师傅负荷不变；</li>
 *   <li>复核通过 → 补发派单事件（师傅负荷 +1）、审核行与工单 review_status 同步落库、写 REVIEW_APPROVED 日志；</li>
 *   <li>复核驳回 → 报修人收到含理由的通知、工单仍是 PENDING_DISPATCH；重提后新审核行 round=2；</li>
 *   <li>一次性决策：二次 approve/reject → 2007；</li>
 *   <li>权限：REPORTER 调复核端点 → 403；</li>
 *   <li>未命中内容回归：直接 PASSED，行为与改造前一致；</li>
 *   <li>待审期间被报修人取消 → 复核通过只落结论、不再派单（不给作废单派活）；</li>
 *   <li>审核轨迹端点按属主判定可读。</li>
 * </ol>
 *
 * <p>命中样本刻意用**确定性隐私规则**（正文出现他人 11 位手机号）构造，不依赖词表种子，
 * 也不受图片抽检比例影响（本类不传图片）。这样用例在任何配置下都可复现。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ContentReviewIntegrationTest {

    private static final String REVIEW_BIZ_ORDER = "ORDER";

    /** 他人的手机号：与报修人注册号不同，确定性触发 PRIVACY 命中 */
    private static final String OTHER_PHONE = "13900008888";
    private static final String OTHER_PHONE_2 = "13900007777";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private WorkOrderService workOrderService;

    @Autowired
    private WorkOrderLogService orderLogService;

    @Autowired
    private ContentReviewRecordService reviewRecordService;

    @Autowired
    private ContentReviewTraceMapper traceMapper;

    @Autowired
    private RepairOrderMapper orderMapper;

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private WorkerProfileMapper workerProfileMapper;

    @Autowired
    private SysNotificationMapper notificationMapper;

    @Autowired
    private SpatialNodeService spatialNodeService;

    @Autowired
    private RepairCategoryService categoryService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long roomId;
    private Long categoryId;
    private String skillCode;
    private SysUser reporter;
    private SysUser otherReporter;
    private SysUser worker;
    private String reporterToken;
    private String otherReporterToken;
    private String dispatcherToken;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        // 手机号必须是 11 位数字：初筛的隐私规则只识别数字串，字母会让人误以为规则没生效
        String digits = String.format("%04d", Math.abs(UUID.randomUUID().hashCode()) % 10000);

        SpatialNode campus = new SpatialNode();
        campus.setName("内容审核校区-" + unique);
        campus.setLevelType(1);
        campus = spatialNodeService.createNode(campus);

        SpatialNode room = new SpatialNode();
        room.setParentId(campus.getId());
        room.setName("内容审核楼201-" + unique);
        room.setLevelType(4);
        room = spatialNodeService.createNode(room);
        roomId = room.getId();

        skillCode = "CR_SKILL_" + unique;
        RepairCategory category = new RepairCategory();
        category.setName("内容审核专用故障-" + unique);
        category.setLevel(2);
        category.setWorkerSkillCode(skillCode);
        category.setSlaHours(24);
        category = categoryService.createCategory(category);
        this.categoryId = category.getId();

        reporter = createUser("cr_reporter_" + unique, "内容审核报修人", "1390002" + digits);
        otherReporter = createUser("cr_other_" + unique, "内容审核他人", "1390003" + digits);
        worker = createUser("cr_worker_" + unique, "内容审核师傅", "1390004" + digits);

        WorkerProfile profile = new WorkerProfile();
        profile.setUserId(worker.getId());
        profile.setSkillCode(skillCode);
        profile.setCampusId(campus.getId());
        profile.setMaxLoad(5);
        profile.setCurrentLoad(0);
        profile.setOnDutyStatus(1);
        profile.setRatingAvg(new BigDecimal("4.80"));
        workerProfileMapper.insert(profile);

        reporterToken = jwtUtils.generateAccessToken(reporter.getId(), reporter.getUsername(), List.of("REPORTER"));
        otherReporterToken = jwtUtils.generateAccessToken(otherReporter.getId(), otherReporter.getUsername(), List.of("REPORTER"));
        dispatcherToken = jwtUtils.generateAccessToken(1L, "admin", List.of("DISPATCHER"));
    }

    private SysUser createUser(String username, String realName, String phone) {
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setRealName(realName);
        user.setPhone(phone);
        user.setPassword(passwordEncoder.encode("password"));
        user.setUserType("STUDENT");
        user.setStatus(1);
        user.setIsFirstLogin(0);
        userMapper.insert(user);
        return user;
    }

    private WorkOrderSubmitResultVO submit(Long userId, String description, List<String> images) {
        WorkOrderSubmitRequest request = new WorkOrderSubmitRequest();
        request.setSpaceId(roomId);
        request.setCategoryId(categoryId);
        request.setDescription(description);
        request.setFaultImages(images);
        request.setAppointmentPeriod("上午08:30-11:30");
        request.setUrgencyLevel(1);
        return workOrderService.submitOrder(userId, request);
    }

    private Long submitHit() {
        WorkOrderSubmitResultVO result = submit(reporter.getId(),
                "楼上漏水到我这，可以联系我邻居 " + OTHER_PHONE + " 确认", null);
        assertEquals("PENDING", result.getReviewStatus(), "命中他人手机号必须转人工复核");
        assertTrue(result.getReviewReason() != null
                        && result.getReviewReason().contains("隐私")
                        && result.getReviewReason().contains("待人工复核"),
                "命中时必须回传可读的分类摘要（不回传词表原文），实际: " + result.getReviewReason());
        assertFalse(result.getReviewReason().contains(OTHER_PHONE), "摘要不得回传命中的号码原文");
        return result.getOrderId();
    }

    private RepairOrder order(Long orderId) {
        return orderMapper.selectById(orderId);
    }

    private int loadOf(Long workerUserId) {
        WorkerProfile profile = workerProfileMapper.selectOne(new LambdaQueryWrapper<WorkerProfile>()
                .eq(WorkerProfile::getUserId, workerUserId));
        return profile == null || profile.getCurrentLoad() == null ? -1 : profile.getCurrentLoad();
    }

    private Long latestReviewId(Long orderId) {
        ContentReviewTraceVO latest = traceMapper.selectLatest(REVIEW_BIZ_ORDER, orderId);
        assertNotNull(latest, "工单 " + orderId + " 缺少审核轨迹行");
        return latest.getId();
    }

    private List<RepairOrderLog> logsOf(Long orderId) {
        return orderLogService.getTimeline(orderId);
    }

    private boolean hasLog(Long orderId, String actionType) {
        return logsOf(orderId).stream().anyMatch(log -> actionType.equals(log.getActionType()));
    }

    private List<SysNotification> notificationsOf(Long userId, Long orderId) {
        return notificationMapper.selectList(new LambdaQueryWrapper<SysNotification>()
                .eq(SysNotification::getUserId, userId)
                .eq(SysNotification::getRelatedOrderId, orderId));
    }

    /** 异步派单/通知是 @Async，断言前轮询到条件成立（最多 timeoutMs），避免固定 sleep 造成的偶发。 */
    private boolean waitUntil(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    private void approveViaApi(Long reviewId, String token) throws Exception {
        mockMvc.perform(post("/api/v1/content-reviews/" + reviewId + "/approve")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"复核无违规内容\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    private void rejectViaApi(Long reviewId, String reason) throws Exception {
        mockMvc.perform(post("/api/v1/content-reviews/" + reviewId + "/reject")
                        .header("Authorization", "Bearer " + dispatcherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of("reason", reason))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("命中初筛的工单不进派单池、不能人工指派（2004）、师傅负荷不变")
    void testSuspectedOrderBlockedFromDispatchPool() {
        Long orderId = submitHit();
        RepairOrder suspected = order(orderId);
        assertEquals("PENDING", suspected.getReviewStatus());
        assertEquals("PENDING_DISPATCH", suspected.getStatus(), "待复核不改工单状态，仍停在待派单");

        List<ContentReviewTraceVO> trace = traceMapper.selectTrace(REVIEW_BIZ_ORDER, orderId);
        assertEquals(1, trace.size(), "命中一次只落一行审核轨迹");
        assertEquals(1, trace.get(0).getSubmitRound());
        assertEquals("PENDING", trace.get(0).getOutcome());
        assertTrue(trace.get(0).getHitRules() != null && trace.get(0).getHitRules().contains("PRIVACY"),
                "命中原因应记录分类（PRIVACY），实际: " + trace.get(0).getHitRules());

        // 把状态改成 MANUAL_DISPATCH，模拟「审核期间被降级/历史数据」——此时派单池与人工指派
        // 是审核闸门唯一的两道拦截点，必须都拦住
        suspected.setStatus("MANUAL_DISPATCH");
        orderMapper.updateById(suspected);

        DispatchPoolQueryRequest poolRequest = new DispatchPoolQueryRequest();
        poolRequest.setCategoryFilter("ALL");
        poolRequest.setKeyword(suspected.getOrderNo());
        DispatchPoolResultVO pool = workOrderService.getDispatchPool(poolRequest);
        assertEquals(0, pool.getOrders().getTotal(), "review_status=PENDING 的工单不得出现在派单池");

        WorkOrderManualDispatchRequest dispatchRequest = new WorkOrderManualDispatchRequest();
        dispatchRequest.setWorkerId(worker.getId());
        dispatchRequest.setDispatchRemark("绕过审核直接指派");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> workOrderService.manualDispatch(orderId, 1L, dispatchRequest));
        assertEquals(ErrorCode.ORDER_CONTENT_UNDER_REVIEW.getCode(), ex.getCode(), "人工指派未通过内容必须 2004");
        assertEquals(0, loadOf(worker.getId()), "被拦截的指派不得占用师傅负荷");

        // 放行审核状态后同一查询立刻能看到 —— 证明过滤条件就是 review_status，而不是别的副作用
        RepairOrder passed = order(orderId);
        passed.setReviewStatus("PASSED");
        orderMapper.updateById(passed);
        assertEquals(1, workOrderService.getDispatchPool(poolRequest).getOrders().getTotal(),
                "review_status=PASSED 后应回到派单池");
    }

    @Test
    @DisplayName("复核通过：补发派单事件（师傅负荷 +1）、工单与审核行同步 PASSED、写 REVIEW_APPROVED")
    void testApproveDispatchesOrder() throws Exception {
        Long orderId = submitHit();
        Long reviewId = latestReviewId(orderId);

        approveViaApi(reviewId, dispatcherToken);

        assertTrue(waitUntil(() -> "ASSIGNED".equals(order(orderId).getStatus()), 5000),
                "复核通过后应补发派单事件并由自动派单引擎指派，实际状态: " + order(orderId).getStatus());
        RepairOrder approved = order(orderId);
        assertEquals("PASSED", approved.getReviewStatus());
        assertEquals(worker.getId(), approved.getWorkerId());
        assertEquals(1, loadOf(worker.getId()), "补发派单后师傅负荷应 +1");
        assertEquals("APPROVED", reviewRecordService.findById(reviewId).getOutcome());
        assertTrue(hasLog(orderId, "REVIEW_APPROVED"), "工单日志应记录 REVIEW_APPROVED");
    }

    @Test
    @DisplayName("复核驳回：通知报修人且工单仍待派单；重提仍命中则新审核行 round=2")
    void testRejectNotifiesAndResubmitIncrementsRound() {
        Long orderId = submitHit();
        Long reviewId = latestReviewId(orderId);

        // 直接走服务层驳回（HTTP 路径由 review-core 的用例覆盖，这里聚焦工单侧副作用）
        workOrderService.decideContentReview(reviewId, false, 1L, "调度主管", "含他人手机号，请删除后重新提交");

        RepairOrder rejected = order(orderId);
        assertEquals("REJECTED", rejected.getReviewStatus());
        assertEquals("PENDING_DISPATCH", rejected.getStatus(), "内容驳回不改工单状态");

        // 学生端「已驳回 + 修改重提」入口依赖列表把 reviewStatus/reviewReason/canCancel 带出来
        com.campus.repair.modules.workorder.vo.DispatchOrderItemVO myItem = workOrderService
                .getMyOrders(reporter.getId(), 1, 50, null).getRecords().stream()
                .filter(item -> orderId.equals(item.getId()))
                .findFirst().orElseThrow();
        assertEquals("REJECTED", myItem.getReviewStatus());
        assertEquals("含他人手机号，请删除后重新提交", myItem.getReviewReason(), "驳回理由必须能在列表上看到");
        assertEquals(Boolean.TRUE, myItem.getCanCancel(), "被驳回的单仍在待派单，报修人可修改重提/取消");

        List<SysNotification> notifications = notificationsOf(reporter.getId(), orderId);
        assertFalse(notifications.isEmpty(), "驳回必须通知报修人");
        assertTrue(notifications.stream().anyMatch(n -> n.getContent() != null && n.getContent().contains("复核")),
                "通知文案应说明复核未通过");

        // 重提仍命中（换了一个他人号码）→ 轮次 +1，旧行保留 REJECTED
        WorkOrderResubmitRequest resubmit = new WorkOrderResubmitRequest();
        resubmit.setDescription("补充说明：现场可联系 " + OTHER_PHONE_2);
        resubmit.setFaultImages(List.of());
        WorkOrderSubmitResultVO resubmitted = workOrderService.resubmitContent(orderId, reporter.getId(), resubmit);
        assertEquals("PENDING", resubmitted.getReviewStatus());

        List<ContentReviewTraceVO> trace = traceMapper.selectTrace(REVIEW_BIZ_ORDER, orderId);
        assertEquals(2, trace.size(), "重提必须新插一行审核轨迹");
        assertEquals(1, trace.get(0).getSubmitRound());
        assertEquals("REJECTED", trace.get(0).getOutcome(), "旧行结论不可被覆盖");
        assertEquals(2, trace.get(1).getSubmitRound());
        assertEquals("PENDING", trace.get(1).getOutcome());
        assertTrue(hasLog(orderId, "REVIEW_RESUBMIT"));

        // 第二轮仍被驳回 → 才能再次重提（重提的准入是 REJECTED，不是「想改就改」）
        workOrderService.decideContentReview(latestReviewId(orderId), false, 1L, "调度主管", "仍有他人号码");
        assertEquals("REJECTED", order(orderId).getReviewStatus());

        // 再重提为干净文案 → 初筛通过，不新增审核行，工单 PASSED 并补发派单
        WorkOrderResubmitRequest clean = new WorkOrderResubmitRequest();
        clean.setDescription("已删除联系方式，卫生间水管接口仍在漏水需要维修");
        WorkOrderSubmitResultVO passed = workOrderService.resubmitContent(orderId, reporter.getId(), clean);
        assertEquals("PASSED", passed.getReviewStatus());
        assertEquals("PASSED", order(orderId).getReviewStatus());
        assertEquals(2, traceMapper.selectTrace(REVIEW_BIZ_ORDER, orderId).size(),
                "初筛通过不落审核行（否则本表会退化成工单的影子表）");
    }

    @Test
    @DisplayName("一次性决策：已决策的审核行再次 approve/reject 均返回 2007")
    void testSecondDecisionRejected() throws Exception {
        Long orderId = submitHit();
        Long reviewId = latestReviewId(orderId);
        approveViaApi(reviewId, dispatcherToken);

        mockMvc.perform(post("/api/v1/content-reviews/" + reviewId + "/approve")
                        .header("Authorization", "Bearer " + dispatcherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.REVIEW_ALREADY_DECIDED.getCode()));

        mockMvc.perform(post("/api/v1/content-reviews/" + reviewId + "/reject")
                        .header("Authorization", "Bearer " + dispatcherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"重复决策\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.REVIEW_ALREADY_DECIDED.getCode()));

        // 审核行结论保持第一次的 APPROVED，未被第二次改写
        assertEquals("APPROVED", reviewRecordService.findById(reviewId).getOutcome());
    }

    @Test
    @DisplayName("权限：REPORTER 调用复核端点 403，且审核行不受影响")
    void testReporterCannotDecideReview() throws Exception {
        Long orderId = submitHit();
        Long reviewId = latestReviewId(orderId);

        mockMvc.perform(post("/api/v1/content-reviews/" + reviewId + "/approve")
                        .header("Authorization", "Bearer " + reporterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        assertEquals("PENDING", reviewRecordService.findById(reviewId).getOutcome(),
                "越权请求不得改变审核结论");
    }

    @Test
    @DisplayName("未命中内容回归：直接 PASSED、无审核轨迹、行为与改造前一致")
    void testCleanContentPassesThrough() {
        WorkOrderSubmitResultVO result = submit(reporter.getId(), "宿舍卫生间水管接口漏水需要维修", null);

        assertEquals("PASSED", result.getReviewStatus());
        assertNull(result.getReviewReason());
        assertTrue(result.getOrderNo().startsWith("BX"), "旧字段口径不变");
        assertEquals("PENDING_DISPATCH", result.getStatus());

        RepairOrder saved = order(result.getOrderId());
        assertEquals("PASSED", saved.getReviewStatus());
        assertTrue(traceMapper.selectTrace(REVIEW_BIZ_ORDER, result.getOrderId()).isEmpty(),
                "初筛通过不应落审核轨迹");
    }

    @Test
    @DisplayName("待审期间报修人取消：复核通过只落结论，不再派单")
    void testApproveAfterReporterCancelDoesNotDispatch() throws Exception {
        Long orderId = submitHit();
        Long reviewId = latestReviewId(orderId);

        WorkOrderReporterCancelRequest cancelRequest = new WorkOrderReporterCancelRequest();
        cancelRequest.setReason("已经自行修好了");
        workOrderService.cancelByReporter(orderId, reporter.getId(), cancelRequest);
        assertEquals("CANCELLED", order(orderId).getStatus());

        approveViaApi(reviewId, dispatcherToken);

        RepairOrder afterApprove = order(orderId);
        assertEquals("CANCELLED", afterApprove.getStatus(), "已取消的工单不得被复核通过改回待派");
        assertEquals("PASSED", afterApprove.getReviewStatus(), "审核结论仍要落库（可追溯）");
        assertNull(afterApprove.getWorkerId(), "已取消的工单不得被派活");
        assertEquals(0, loadOf(worker.getId()), "已取消的工单不得占用师傅负荷");
        assertEquals("APPROVED", reviewRecordService.findById(reviewId).getOutcome());
        assertTrue(hasLog(orderId, "REVIEW_APPROVED"));
    }

    @Test
    @DisplayName("审核轨迹端点：属主/调度可读，无关用户 403")
    void testReviewTraceEndpointOwnership() throws Exception {
        Long orderId = submitHit();

        mockMvc.perform(get("/api/v1/work-orders/" + orderId + "/reviews")
                        .header("Authorization", "Bearer " + reporterToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].submitRound").value(1))
                .andExpect(jsonPath("$.data[0].outcome").value("PENDING"))
                .andExpect(jsonPath("$.data[0].hitRules").exists());

        mockMvc.perform(get("/api/v1/work-orders/" + orderId + "/reviews")
                        .header("Authorization", "Bearer " + dispatcherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/v1/work-orders/" + orderId + "/reviews")
                        .header("Authorization", "Bearer " + otherReporterToken))
                .andExpect(status().isForbidden());
    }
}
