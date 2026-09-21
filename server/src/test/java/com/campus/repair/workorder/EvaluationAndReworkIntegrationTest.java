package com.campus.repair.workorder;

import com.campus.repair.common.exception.BusinessException;
import com.campus.repair.common.exception.ErrorCode;
import com.campus.repair.modules.spatial.entity.RepairCategory;
import com.campus.repair.modules.spatial.entity.SpatialNode;
import com.campus.repair.modules.spatial.service.RepairCategoryService;
import com.campus.repair.modules.spatial.service.SpatialNodeService;
import com.campus.repair.modules.user.entity.SysUser;
import com.campus.repair.modules.user.entity.WorkerProfile;
import com.campus.repair.modules.user.mapper.SysUserMapper;
import com.campus.repair.modules.user.mapper.WorkerProfileMapper;
import com.campus.repair.modules.workorder.dto.*;
import com.campus.repair.modules.workorder.entity.RepairOrder;
import com.campus.repair.modules.workorder.entity.RepairOrderEvaluation;
import com.campus.repair.modules.workorder.entity.RepairOrderLog;
import com.campus.repair.modules.workorder.enums.OrderStatusEnum;
import com.campus.repair.modules.workorder.mapper.RepairOrderEvaluationMapper;
import com.campus.repair.modules.workorder.mapper.RepairOrderMapper;
import com.campus.repair.modules.workorder.service.WorkOrderLogService;
import com.campus.repair.modules.workorder.service.WorkOrderService;
import com.campus.repair.modules.workorder.task.WorkOrderScheduledJobs;
import com.campus.repair.modules.workorder.vo.DispatchPoolResultVO;
import com.campus.repair.modules.workorder.vo.WorkOrderEvaluationVO;
import com.campus.repair.modules.workorder.vo.WorkOrderSubmitResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class EvaluationAndReworkIntegrationTest {

    @Autowired
    private WorkOrderService workOrderService;

    @Autowired
    private RepairOrderMapper orderMapper;

    @Autowired
    private RepairOrderEvaluationMapper evaluationMapper;

    @Autowired
    private WorkerProfileMapper workerProfileMapper;

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private SpatialNodeService spatialNodeService;

    @Autowired
    private RepairCategoryService categoryService;

    @Autowired
    private WorkOrderLogService logService;

    @Autowired
    private WorkOrderScheduledJobs scheduledJobs;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long testCampusId;
    private Long testRoomId;
    private Long testCategoryId;
    private SysUser studentUser;
    private SysUser workerOriginal;
    private SysUser workerSenior;
    private SysUser adminUser;

    @BeforeEach
    void setUp() {
        SpatialNode campus = new SpatialNode();
        campus.setName("验收评价校区");
        campus.setLevelType(1);
        campus = spatialNodeService.createNode(campus);
        testCampusId = campus.getId();

        SpatialNode room = new SpatialNode();
        room.setParentId(testCampusId);
        room.setName("评价质检中心201");
        room.setLevelType(4);
        room = spatialNodeService.createNode(room);
        testRoomId = room.getId();

        RepairCategory cat = new RepairCategory();
        cat.setName("验收专用门窗故障");
        cat.setLevel(2);
        cat.setWorkerSkillCode("MANUAL_EVAL_SKILL");
        cat.setSlaHours(24);
        cat = categoryService.createCategory(cat);
        testCategoryId = cat.getId();

        studentUser = createTestUser("student_eval", "赵同学", "13899990001", "STUDENT");
        workerOriginal = createTestUser("worker_eval_orig", "张师傅(原工)", "13899990002", "LOGISTICS");
        WorkerProfile p1 = new WorkerProfile();
        p1.setUserId(workerOriginal.getId());
        p1.setSkillCode("MANUAL_EVAL_SKILL");
        p1.setCampusId(testCampusId);
        p1.setMaxLoad(5);
        p1.setCurrentLoad(0);
        p1.setOnDutyStatus(1);
        p1.setRatingAvg(new BigDecimal("4.80"));
        workerProfileMapper.insert(p1);

        workerSenior = createTestUser("worker_eval_snr", "孙高级技师", "13899990003", "LOGISTICS");
        WorkerProfile p2 = new WorkerProfile();
        p2.setUserId(workerSenior.getId());
        p2.setSkillCode("MANUAL_EVAL_SKILL");
        p2.setCampusId(testCampusId);
        p2.setMaxLoad(5);
        p2.setCurrentLoad(0);
        p2.setOnDutyStatus(1);
        p2.setRatingAvg(new BigDecimal("4.99"));
        workerProfileMapper.insert(p2);

        adminUser = createTestUser("admin_eval", "李后勤主管", "13899990004", "LOGISTICS");
    }

    private SysUser createTestUser(String username, String realName, String phone, String role) {
        SysUser u = new SysUser();
        u.setUsername(username);
        u.setRealName(realName);
        u.setPhone(phone);
        u.setPassword(passwordEncoder.encode("password"));
        u.setUserType(role);
        u.setStatus(1);
        u.setIsFirstLogin(0);
        userMapper.insert(u);
        return u;
    }

    private RepairOrder createPendingAcceptanceOrder() {
        // 1. 学生提单
        WorkOrderSubmitRequest sub = new WorkOrderSubmitRequest();
        sub.setSpaceId(testRoomId);
        sub.setCategoryId(testCategoryId);
        sub.setDescription("实训中心门窗把手松动打滑");
        sub.setUrgencyLevel(1);
        sub.setAppointmentPeriod("上午08:30-11:30");
        sub.setApplicantPhone(studentUser.getPhone());
        WorkOrderSubmitResultVO res = workOrderService.submitOrder(studentUser.getId(), sub);
        Long orderId = res.getOrderId();

        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        // 2. 调度指派给 workerOriginal -> ASSIGNED
        WorkOrderManualDispatchRequest dispatchReq = new WorkOrderManualDispatchRequest();
        dispatchReq.setWorkerId(workerOriginal.getId());
        dispatchReq.setDispatchRemark("安排张师傅上门维修");
        workOrderService.manualDispatch(orderId, adminUser.getId(), dispatchReq);

        // 3. 师傅接单出发 -> IN_PROGRESS
        workOrderService.acceptOrder(orderId, workerOriginal.getId());

        // 4. 师傅完工填报 -> PENDING_ACCEPTANCE
        WorkOrderFinishRequest finishReq = new WorkOrderFinishRequest();
        finishReq.setRepairSummary("更换门把手固定螺栓并加注润滑剂，试开闭5次顺畅良好");
        finishReq.setRepairImages(List.of("http://test.com/finish_watermark.jpg"));
        workOrderService.finishOrder(orderId, workerOriginal.getId(), finishReq);

        return orderMapper.selectById(orderId);
    }

    @Test
    @DisplayName("正常评价流转闭环（5星好评）：状态跃迁至 COMPLETED 并固化 closedTime 与审计日志")
    void testEvaluateOrderHighRatingCompleted() {
        RepairOrder order = createPendingAcceptanceOrder();
        assertEquals(OrderStatusEnum.PENDING_ACCEPTANCE.name(), order.getStatus());

        // 师生提交 5 星评价
        WorkOrderEvaluateRequest evalReq = new WorkOrderEvaluateRequest();
        evalReq.setRatingStar(5);
        evalReq.setRatingTags(List.of("准时到达", "技术过硬", "清理彻底"));
        evalReq.setComment("张师傅上门非常迅速，门锁完全修好了，点赞！");

        workOrderService.evaluateOrder(order.getId(), studentUser.getId(), evalReq);

        // 验证工单闭环结单
        RepairOrder completed = orderMapper.selectById(order.getId());
        assertEquals(OrderStatusEnum.COMPLETED.name(), completed.getStatus());
        assertNotNull(completed.getClosedTime(), "结单时间戳必须固化");

        // 验证评价详情（按属主视角：该单的报修人本人）
        WorkOrderEvaluationVO detail = workOrderService.getOrderEvaluationDetail(
                order.getId(), studentUser.getId(), false);
        assertEquals(5, detail.getRatingStar());
        assertEquals("张师傅上门非常迅速，门锁完全修好了，点赞！", detail.getEvaluationComment());
        assertTrue(detail.getRatingTags().contains("准时到达"));
        assertEquals(0, detail.getReworkFlag());

        // 验证审计日志
        List<RepairOrderLog> logs = logService.getTimeline(order.getId());
        RepairOrderLog last = logs.get(logs.size() - 1);
        assertEquals("MANUAL_ACCEPT", last.getActionType());
        assertEquals("PENDING_ACCEPTANCE", last.getFromStatus());
        assertEquals("COMPLETED", last.getToStatus());
        assertTrue(last.getReasonOrRemark().contains("5 星"));
    }

    @Test
    @DisplayName("差评流转与返工拦截（2星差评）：阻断结单，流转为 REWORK_PENDING 并在调度池置顶")
    void testEvaluateOrderLowRatingTriggersRework() {
        RepairOrder order = createPendingAcceptanceOrder();

        // 差评但未填写说明，必须抛出参数校验异常
        WorkOrderEvaluateRequest badReqNoComment = new WorkOrderEvaluateRequest();
        badReqNoComment.setRatingStar(2);
        assertThrows(BusinessException.class, () ->
                workOrderService.evaluateOrder(order.getId(), studentUser.getId(), badReqNoComment));

        // 正常提交带说明的 2 星差评
        WorkOrderEvaluateRequest evalReq = new WorkOrderEvaluateRequest();
        evalReq.setRatingStar(2);
        evalReq.setComment("门把手还是松动的，开关门依然卡顿，根本没修好！");

        workOrderService.evaluateOrder(order.getId(), studentUser.getId(), evalReq);

        // 验证工单跃迁为 REWORK_PENDING
        RepairOrder reworkOrder = orderMapper.selectById(order.getId());
        assertEquals(OrderStatusEnum.REWORK_PENDING.name(), reworkOrder.getStatus());
        assertEquals(1, reworkOrder.getReworkCount(), "返工计数应增加至 1");
        assertNull(reworkOrder.getClosedTime(), "返工单绝不可有 closedTime 闭环时间");

        // 验证调度中心待办工单池筛选：reworkCount 红点统计与置顶
        DispatchPoolQueryRequest poolReq = new DispatchPoolQueryRequest();
        poolReq.setCategoryFilter("REWORK");
        DispatchPoolResultVO poolRes = workOrderService.getDispatchPool(poolReq);
        assertTrue(poolRes.getCounts().getReworkCount() >= 1);
        assertTrue(poolRes.getOrders().getRecords().stream().anyMatch(o -> o.getId().equals(order.getId())));
    }

    @Test
    @DisplayName("后勤主管仲裁退回原师傅：工单重新指派给原师傅，状态变为 ASSIGNED，师傅负荷原子递增")
    void testArbitrateReworkRetryOriginalWorker() {
        RepairOrder order = createPendingAcceptanceOrder();

        // 师生提交差评触发返工
        WorkOrderEvaluateRequest evalReq = new WorkOrderEvaluateRequest();
        evalReq.setRatingStar(1);
        evalReq.setComment("门锁完全坏了，要求重新派人！");
        workOrderService.evaluateOrder(order.getId(), studentUser.getId(), evalReq);

        WorkerProfile p1Before = workerProfileMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkerProfile>()
                        .eq(WorkerProfile::getUserId, workerOriginal.getId()));
        assertEquals(0, p1Before.getCurrentLoad(), "完工后负荷已归零");

        // 主管仲裁：退回原师傅限期整改
        WorkOrderArbitrateReworkRequest arbReq = new WorkOrderArbitrateReworkRequest();
        arbReq.setArbitrationDecision("RETRY_ORIGINAL_WORKER");
        arbReq.setArbitrationNote("已电话联系张师傅，要求今晚18:00前再次上门紧固螺栓并更换锁芯垫片");

        workOrderService.arbitrateRework(order.getId(), adminUser.getId(), arbReq);

        // 验证工单跃迁至 ASSIGNED，仍然指派给 workerOriginal
        RepairOrder arbitrated = orderMapper.selectById(order.getId());
        assertEquals(OrderStatusEnum.ASSIGNED.name(), arbitrated.getStatus());
        assertEquals(workerOriginal.getId(), arbitrated.getWorkerId());

        // 验证原师傅负荷原子加 1
        WorkerProfile p1After = workerProfileMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkerProfile>()
                        .eq(WorkerProfile::getUserId, workerOriginal.getId()));
        assertEquals(1, p1After.getCurrentLoad());

        // 验证流转审计日志
        List<RepairOrderLog> logs = logService.getTimeline(order.getId());
        RepairOrderLog last = logs.get(logs.size() - 1);
        assertEquals("REWORK_ASSIGN", last.getActionType());
        assertEquals("REWORK_PENDING", last.getFromStatus());
        assertEquals("ASSIGNED", last.getToStatus());
        assertTrue(last.getReasonOrRemark().contains("退回原维修师傅限期整改"));
    }

    @Test
    @DisplayName("后勤主管仲裁换派新技师：工单指派给高级技师，原师傅不受影响，新技师负荷原子递增")
    void testArbitrateReworkReassignNewWorker() {
        RepairOrder order = createPendingAcceptanceOrder();

        // 师生差评触发返工
        WorkOrderEvaluateRequest evalReq = new WorkOrderEvaluateRequest();
        evalReq.setRatingStar(1);
        evalReq.setComment("技术不过关，反复修不好");
        workOrderService.evaluateOrder(order.getId(), studentUser.getId(), evalReq);

        // 主管决策换派高级技师
        WorkOrderArbitrateReworkRequest arbReq = new WorkOrderArbitrateReworkRequest();
        arbReq.setArbitrationDecision("REASSIGN_NEW_WORKER");
        arbReq.setTargetWorkerId(workerSenior.getId());
        arbReq.setArbitrationNote("指派金牌技师孙师傅处理疑难门锁");

        workOrderService.arbitrateRework(order.getId(), adminUser.getId(), arbReq);

        RepairOrder reassigned = orderMapper.selectById(order.getId());
        assertEquals(OrderStatusEnum.ASSIGNED.name(), reassigned.getStatus());
        assertEquals(workerSenior.getId(), reassigned.getWorkerId());
        assertEquals(workerSenior.getRealName(), reassigned.getWorkerName());

        WorkerProfile snrProfile = workerProfileMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkerProfile>()
                        .eq(WorkerProfile::getUserId, workerSenior.getId()));
        assertEquals(1, snrProfile.getCurrentLoad(), "高级技师负荷应原子加 1");
    }

    @Test
    @DisplayName("72小时超时自动核销定时任务调度：超期待验收工单幂等自动闭环结单")
    void testScheduledJobAutoConfirmAcceptance() {
        RepairOrder order = createPendingAcceptanceOrder();

        // 人为将完工时间修改为 73 小时前
        order.setFinishTime(LocalDateTime.now().minusHours(73));
        orderMapper.updateById(order);

        // 手动触发定时扫描任务
        scheduledJobs.scanAndAutoCompleteOrders();

        // 验证工单已被自动确认结单
        RepairOrder autoClosed = orderMapper.selectById(order.getId());
        assertEquals(OrderStatusEnum.COMPLETED.name(), autoClosed.getStatus());
        assertNotNull(autoClosed.getClosedTime());

        // 验证评价记录带超时标记，且不强行虚增评分（ratingStar 为 null，避免虚假好评）
        WorkOrderEvaluationVO eval = workOrderService.getOrderEvaluationDetail(
                order.getId(), studentUser.getId(), false);
        assertEquals(1, eval.getIsAutoConfirmed(), "应标记为72小时超时自动确认");
        assertNull(eval.getRatingStar(), "自动确认结单不应强设虚假评分");
        assertTrue(eval.getEvaluationComment().contains("72小时"));

        // 验证定时核销幂等性：再次执行任务不应抛错或重复翻转
        assertDoesNotThrow(() -> scheduledJobs.scanAndAutoCompleteOrders());
    }

    @Test
    @DisplayName("非申请人评价越权拦截：他人账号评价抛出 AccessDeniedException")
    void testEvaluateOrderAccessDenied() {
        RepairOrder order = createPendingAcceptanceOrder();

        WorkOrderEvaluateRequest evalReq = new WorkOrderEvaluateRequest();
        evalReq.setRatingStar(5);
        evalReq.setComment("冒名顶替评价");

        // 另外一个学生账号尝试评价该工单
        assertThrows(AccessDeniedException.class, () ->
                workOrderService.evaluateOrder(order.getId(), 99999L, evalReq));
    }
}
