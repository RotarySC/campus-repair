package com.campus.repair.workorder;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.repair.common.exception.BusinessException;
import com.campus.repair.common.exception.ErrorCode;
import com.campus.repair.framework.util.JwtUtils;
import com.campus.repair.modules.notification.entity.SysNotification;
import com.campus.repair.modules.notification.mapper.SysNotificationMapper;
import com.campus.repair.modules.spatial.entity.RepairCategory;
import com.campus.repair.modules.spatial.entity.SpatialNode;
import com.campus.repair.modules.spatial.service.RepairCategoryService;
import com.campus.repair.modules.spatial.service.SpatialNodeService;
import com.campus.repair.modules.user.entity.SysUser;
import com.campus.repair.modules.user.entity.WorkerProfile;
import com.campus.repair.modules.user.mapper.SysUserMapper;
import com.campus.repair.modules.user.mapper.WorkerProfileMapper;
import com.campus.repair.modules.workorder.dto.WorkOrderCancelRequest;
import com.campus.repair.modules.workorder.dto.WorkOrderManualDispatchRequest;
import com.campus.repair.modules.workorder.dto.WorkOrderReporterCancelRequest;
import com.campus.repair.modules.workorder.dto.WorkOrderSubmitRequest;
import com.campus.repair.modules.workorder.entity.RepairOrder;
import com.campus.repair.modules.workorder.entity.RepairOrderLog;
import com.campus.repair.modules.workorder.mapper.RepairOrderMapper;
import com.campus.repair.modules.workorder.service.ReporterCancelPolicy;
import com.campus.repair.modules.workorder.service.WorkOrderLogService;
import com.campus.repair.modules.workorder.service.WorkOrderService;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 报修人「接单前自助取消」（Phase 03）端到端回归。
 *
 * <p>判定表（{@link ReporterCancelPolicy}）：
 * <ul>
 *   <li>{@code PENDING_DISPATCH} / {@code MANUAL_DISPATCH}：能取消（还没人接）；</li>
 *   <li>{@code ASSIGNED} 且 {@code accepted_time} 为空：能取消（已指派但师傅未接单），并**回落师傅负荷**；</li>
 *   <li>{@code IN_PROGRESS} 及之后：不能取消（2006），只能走调度核实作废或完工；</li>
 *   <li>{@code ASSIGNED} 但 {@code accepted_time} 已写（畸形数据）：拒绝——师傅其实已经接过单。</li>
 * </ul>
 *
 * <p>取消复用 {@code CANCELLED} 状态，靠审计 {@code CANCEL_BY_REPORTER} + {@code REPORTER}
 * 与调度作废（{@code CANCEL} + {@code DISPATCHER}）区分主体，不给状态机加第七个状态。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkOrderReporterCancelIntegrationTest {

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
    private SysUser reporter;
    private SysUser otherReporter;
    private SysUser worker;
    private String reporterToken;
    private String otherReporterToken;

    @BeforeEach
    void setUp() {
        String unique = UUID.randomUUID().toString().substring(0, 8);
        String digits = String.format("%04d", Math.abs(UUID.randomUUID().hashCode()) % 10000);

        SpatialNode campus = new SpatialNode();
        campus.setName("取消回归校区-" + unique);
        campus.setLevelType(1);
        campus = spatialNodeService.createNode(campus);

        SpatialNode room = new SpatialNode();
        room.setParentId(campus.getId());
        room.setName("取消回归楼301-" + unique);
        room.setLevelType(4);
        room = spatialNodeService.createNode(room);
        roomId = room.getId();

        // 刻意让故障分类的工种**没有在岗师傅**：否则提单的异步自动派单会在用例断言前把单改成
        // ASSIGNED 并占用负荷，取消窗口的前提（PENDING_DISPATCH）就没了。取消用例要的是
        // 可控的起始状态，不是自动派单链路本身。
        RepairCategory category = new RepairCategory();
        category.setName("取消回归故障-" + unique);
        category.setLevel(2);
        category.setWorkerSkillCode("CANCEL_NOBODY_" + unique);
        category.setSlaHours(24);
        category = categoryService.createCategory(category);
        categoryId = category.getId();

        reporter = createUser("cancel_reporter_" + unique, "取消回归报修人", "1390012" + digits, "STUDENT");
        otherReporter = createUser("cancel_other_" + unique, "取消回归他人", "1390013" + digits, "STUDENT");
        worker = createUser("cancel_worker_" + unique, "取消回归师傅", "1390014" + digits, "LOGISTICS");

        WorkerProfile profile = new WorkerProfile();
        profile.setUserId(worker.getId());
        profile.setSkillCode("CANCEL_WORKER_" + unique); // 与分类工种不同，自动派单不会选中他
        profile.setCampusId(campus.getId());
        profile.setMaxLoad(5);
        profile.setCurrentLoad(0);
        profile.setOnDutyStatus(1);
        profile.setRatingAvg(new BigDecimal("4.90"));
        workerProfileMapper.insert(profile);

        reporterToken = jwtUtils.generateAccessToken(reporter.getId(), reporter.getUsername(), List.of("REPORTER"));
        otherReporterToken = jwtUtils.generateAccessToken(otherReporter.getId(), otherReporter.getUsername(), List.of("REPORTER"));
    }

    private SysUser createUser(String username, String realName, String phone, String branch) {
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setRealName(realName);
        user.setPhone(phone);
        user.setPassword(passwordEncoder.encode("password"));
        user.setUserType(branch);
        user.setStatus(1);
        user.setIsFirstLogin(0);
        userMapper.insert(user);
        return user;
    }

    /**
     * 提单并把工单**稳定在 PENDING_DISPATCH**。
     *
     * <p>提单会异步触发自动派单：本类分类没有在岗师傅，引擎会把它降级成 MANUAL_DISPATCH
     * （不占负荷）。先等这次异步降级落库，再显式写回 PENDING_DISPATCH，
     * 用例才能从「还没派出去」这个确定起点开始验证取消窗口。
     */
    private Long submitOrder() {
        WorkOrderSubmitRequest request = new WorkOrderSubmitRequest();
        request.setSpaceId(roomId);
        request.setCategoryId(categoryId);
        request.setDescription("取消回归：卫生间水管接口持续漏水");
        request.setAppointmentPeriod("上午08:30-11:30");
        request.setUrgencyLevel(1);
        WorkOrderSubmitResultVO result = workOrderService.submitOrder(reporter.getId(), request);
        Long orderId = result.getOrderId();

        waitUntil(() -> !"PENDING_DISPATCH".equals(order(orderId).getStatus()), 3000);
        orderMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<RepairOrder>()
                .eq(RepairOrder::getId, orderId)
                .set(RepairOrder::getStatus, "PENDING_DISPATCH")
                .set(RepairOrder::getDispatchType, null)
                .set(RepairOrder::getWorkerId, null)
                .set(RepairOrder::getWorkerName, null)
                .set(RepairOrder::getWorkerPhone, null)
                .set(RepairOrder::getAcceptedTime, null));
        return orderId;
    }

    private RepairOrder order(Long orderId) {
        return orderMapper.selectById(orderId);
    }

    private int loadOf(Long workerUserId) {
        WorkerProfile profile = workerProfileMapper.selectOne(new LambdaQueryWrapper<WorkerProfile>()
                .eq(WorkerProfile::getUserId, workerUserId));
        return profile == null || profile.getCurrentLoad() == null ? -1 : profile.getCurrentLoad();
    }

    private void dispatchToWorker(Long orderId) {
        WorkOrderManualDispatchRequest request = new WorkOrderManualDispatchRequest();
        request.setWorkerId(worker.getId());
        request.setDispatchRemark("取消回归：指派师傅");
        workOrderService.manualDispatch(orderId, 1L, request);
    }

    private void cancelViaApi(Long orderId, String token, String body) throws Exception {
        mockMvc.perform(post("/api/v1/work-orders/" + orderId + "/cancel-by-reporter")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body == null ? "{}" : body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

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

    @Test
    @DisplayName("PENDING_DISPATCH 本人取消：200、状态 CANCELLED、默认原因、审计 CANCEL_BY_REPORTER/REPORTER")
    void testCancelPendingDispatchByOwner() throws Exception {
        Long orderId = submitOrder();

        cancelViaApi(orderId, reporterToken, null);

        RepairOrder cancelled = order(orderId);
        assertEquals("CANCELLED", cancelled.getStatus());
        assertEquals("报修人自行取消", cancelled.getCancelReason(), "未填理由时写默认原因");

        List<RepairOrderLog> logs = orderLogService.getTimeline(orderId);
        RepairOrderLog cancelLog = logs.stream()
                .filter(log -> "CANCEL_BY_REPORTER".equals(log.getActionType()))
                .reduce((first, second) -> second)
                .orElse(null);
        assertNotNull(cancelLog, "缺少 CANCEL_BY_REPORTER 审计节点");
        assertEquals("REPORTER", cancelLog.getOperatorRole(), "报修人自取消必须以 REPORTER 身份记账");
        assertEquals("PENDING_DISPATCH", cancelLog.getFromStatus());
        assertEquals("CANCELLED", cancelLog.getToStatus());
    }

    @Test
    @DisplayName("MANUAL_DISPATCH（自动派单降级后）本人取消：200")
    void testCancelManualDispatchByOwner() throws Exception {
        Long orderId = submitOrder();
        RepairOrder manual = order(orderId);
        manual.setStatus("MANUAL_DISPATCH");
        manual.setDispatchType("MANUAL");
        orderMapper.updateById(manual);

        WorkOrderReporterCancelRequest request = new WorkOrderReporterCancelRequest();
        request.setReason("师傅还没来，我已经自己修好了");
        cancelViaApi(orderId, reporterToken, objectMapper.writeValueAsString(request));

        assertEquals("CANCELLED", order(orderId).getStatus());
        assertEquals("师傅还没来，我已经自己修好了", order(orderId).getCancelReason());
    }

    @Test
    @DisplayName("ASSIGNED 未接单取消：200、师傅负荷回落、师傅收到取消通知")
    void testCancelAssignedReleasesWorkerLoadAndNotifiesWorker() throws Exception {
        Long orderId = submitOrder();
        dispatchToWorker(orderId);
        assertEquals(1, loadOf(worker.getId()), "指派后师傅负荷应为 1");

        cancelViaApi(orderId, reporterToken, null);

        assertEquals("CANCELLED", order(orderId).getStatus());
        assertEquals(0, loadOf(worker.getId()), "报修人取消必须回落师傅负荷，否则额度被作废单永久占用");
        assertTrue(waitUntil(() -> !notificationMapper.selectList(new LambdaQueryWrapper<SysNotification>()
                        .eq(SysNotification::getUserId, worker.getId())
                        .eq(SysNotification::getRelatedOrderId, orderId)).isEmpty(), 3000),
                "指派师傅必须收到「报修人已取消」通知，否则可能白跑一趟");
        List<SysNotification> workerNotifications = notificationMapper.selectList(
                new LambdaQueryWrapper<SysNotification>()
                        .eq(SysNotification::getUserId, worker.getId())
                        .eq(SysNotification::getRelatedOrderId, orderId));
        assertTrue(workerNotifications.stream().anyMatch(n -> n.getTitle() != null && n.getTitle().contains("取消")));
    }

    @Test
    @DisplayName("IN_PROGRESS（师傅已接单）取消：2006")
    void testCancelInProgressRejected() throws Exception {
        Long orderId = submitOrder();
        dispatchToWorker(orderId);
        workOrderService.acceptOrder(orderId, worker.getId());
        assertEquals("IN_PROGRESS", order(orderId).getStatus());

        mockMvc.perform(post("/api/v1/work-orders/" + orderId + "/cancel-by-reporter")
                        .header("Authorization", "Bearer " + reporterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.ORDER_CANCEL_NOT_ALLOWED.getCode()));

        assertEquals("IN_PROGRESS", order(orderId).getStatus(), "已上门作业不得被报修人取消");
    }

    @Test
    @DisplayName("他人取消：403（属主判定）")
    void testOtherUserCancelForbidden() throws Exception {
        Long orderId = submitOrder();

        mockMvc.perform(post("/api/v1/work-orders/" + orderId + "/cancel-by-reporter")
                        .header("Authorization", "Bearer " + otherReporterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        assertEquals("PENDING_DISPATCH", order(orderId).getStatus());
    }

    @Test
    @DisplayName("重复取消：第二次 2006，且审计只落一条（防双写回归）")
    void testCancelTwiceRejected() throws Exception {
        Long orderId = submitOrder();
        cancelViaApi(orderId, reporterToken, null);

        mockMvc.perform(post("/api/v1/work-orders/" + orderId + "/cancel-by-reporter")
                        .header("Authorization", "Bearer " + reporterToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.ORDER_CANCEL_NOT_ALLOWED.getCode()));

        // 串行回归：状态变更必须靠条件更新只落一次；第二次若还能写成功，
        // 说明取消又退回了「读快照 + 整行回写」，并发下就会变成多次成功。
        long cancelLogs = orderLogService.getTimeline(orderId).stream()
                .filter(log -> "CANCEL_BY_REPORTER".equals(log.getActionType()))
                .count();
        assertEquals(1, cancelLogs, "重复取消不得重复落审计，实际 CANCEL_BY_REPORTER 日志数=" + cancelLogs);
    }

    @Test
    @DisplayName("串行回归：调度作废后师傅再接单必须失败，且不得改写状态/时间戳/审计")
    void testAcceptAfterDispatcherCancelFails() {
        Long orderId = submitOrder();
        dispatchToWorker(orderId);
        assertEquals(1, loadOf(worker.getId()), "指派后师傅负荷应为 1");

        WorkOrderCancelRequest cancel = new WorkOrderCancelRequest();
        cancel.setCancelReason("串行回归：调度核实作废");
        workOrderService.cancelOrder(orderId, 1L, cancel);
        assertEquals("CANCELLED", order(orderId).getStatus());
        assertEquals(0, loadOf(worker.getId()), "作废必须回落负荷");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> workOrderService.acceptOrder(orderId, worker.getId()));
        assertEquals(ErrorCode.ORDER_STATE_TRANSITION_INVALID.getCode(), ex.getCode(),
                "作废后接单必须被既有非法状态码拒绝（2002），否则说明接单又退回整行回写");

        RepairOrder after = order(orderId);
        assertEquals("CANCELLED", after.getStatus(), "失败的接单不得改写工单状态");
        assertTrue(after.getAcceptedTime() == null, "失败的接单不得写 accepted_time");
        long acceptLogs = orderLogService.getTimeline(orderId).stream()
                .filter(log -> "ACCEPT".equals(log.getActionType()))
                .count();
        assertEquals(0, acceptLogs, "失败的接单不得落审计，实际 ACCEPT 日志数=" + acceptLogs);
    }

    @Test
    @DisplayName("调度核实作废回归：动作仍是 CANCEL + DISPATCHER，不受报修人取消改造影响")
    void testDispatcherCancelUnchanged() {
        Long orderId = submitOrder();
        RepairOrder manual = order(orderId);
        manual.setStatus("MANUAL_DISPATCH");
        orderMapper.updateById(manual);

        WorkOrderCancelRequest request = new WorkOrderCancelRequest();
        request.setCancelReason("电话核实为误报，予以作废");
        workOrderService.cancelOrder(orderId, 1L, request);

        assertEquals("CANCELLED", order(orderId).getStatus());
        List<RepairOrderLog> logs = orderLogService.getTimeline(orderId);
        RepairOrderLog cancelLog = logs.stream()
                .filter(log -> "CANCEL".equals(log.getActionType()))
                .reduce((first, second) -> second)
                .orElse(null);
        assertNotNull(cancelLog, "调度作废的审计动作词必须保持 CANCEL");
        assertEquals("DISPATCHER", cancelLog.getOperatorRole());
        assertFalse(logs.stream().anyMatch(log -> "CANCEL_BY_REPORTER".equals(log.getActionType())),
                "调度作废不得被记成报修人取消");
    }

    @Test
    @DisplayName("畸形数据防御：ASSIGNED 但 accepted_time 已写 → 拒绝取消（2006）")
    void testMalformedAssignedWithAcceptedTimeRejected() {
        Long orderId = submitOrder();
        RepairOrder malformed = order(orderId);
        malformed.setStatus("ASSIGNED");
        malformed.setWorkerId(worker.getId());
        malformed.setWorkerName(worker.getRealName());
        malformed.setWorkerPhone(worker.getPhone());
        malformed.setAcceptedTime(LocalDateTime.now()); // 师傅其实已接过单，只是状态没推进
        orderMapper.updateById(malformed);

        assertFalse(ReporterCancelPolicy.canCancel(order(orderId)),
                "accepted_time 已写的 ASSIGNED 不在「接单前」窗口内");

        WorkOrderReporterCancelRequest request = new WorkOrderReporterCancelRequest();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> workOrderService.cancelByReporter(orderId, reporter.getId(), request));
        assertEquals(ErrorCode.ORDER_CANCEL_NOT_ALLOWED.getCode(), ex.getCode());
        assertEquals("ASSIGNED", order(orderId).getStatus());
    }
}
