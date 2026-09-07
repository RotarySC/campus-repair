package com.campus.repair.notification;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.repair.CampusRepairApplication;
import com.campus.repair.modules.notification.dto.NotificationPayload;
import com.campus.repair.modules.notification.dto.NotificationQueryRequest;
import com.campus.repair.modules.notification.entity.SysNotification;
import com.campus.repair.modules.notification.service.NotificationService;
import com.campus.repair.modules.notification.spi.Notifier;
import com.campus.repair.modules.notification.vo.NotificationVO;
import com.campus.repair.modules.workorder.dto.WorkOrderCancelRequest;
import com.campus.repair.modules.workorder.dto.WorkOrderEvaluateRequest;
import com.campus.repair.modules.workorder.dto.WorkOrderManualDispatchRequest;
import com.campus.repair.modules.workorder.dto.WorkOrderSubmitRequest;
import com.campus.repair.modules.workorder.entity.RepairOrderLog;
import com.campus.repair.modules.workorder.service.WorkOrderService;
import com.campus.repair.modules.workorder.vo.WorkOrderSubmitResultVO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@SpringBootTest(classes = CampusRepairApplication.class)
@ActiveProfiles("test")
public class NotificationIntegrationTest {

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private WorkOrderService workOrderService;

    @Autowired
    private com.campus.repair.modules.spatial.service.SpatialNodeService spatialNodeService;

    @Autowired
    private com.campus.repair.modules.spatial.service.RepairCategoryService categoryService;

    @Autowired
    private com.campus.repair.modules.user.mapper.SysUserMapper userMapper;

    @Autowired
    private com.campus.repair.modules.user.mapper.WorkerProfileMapper workerProfileMapper;

    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("测试站内消息发送、未读计数统计、标记已读与一键全部已读")
    @Transactional
    public void testNotificationLifecycle() {
        Long testUserId = 99901L;

        // 初始未读数应为 0
        long unread0 = notificationService.getUnreadCount(testUserId);
        Assertions.assertEquals(0, unread0);

        // 发送 3 条通知
        SysNotification n1 = notificationService.sendNotification(
                testUserId, "测试通知1", "内容1", "ORDER_FLOW", 1001L);
        SysNotification n2 = notificationService.sendNotification(
                testUserId, "测试通知2", "内容2", "ORDER_DISPATCH", 1002L);
        SysNotification n3 = notificationService.sendNotification(
                testUserId, "测试通知3", "内容3", "REWORK_ALERT", 1003L);

        Assertions.assertNotNull(n1.getId());
        Assertions.assertNotNull(n2.getId());
        Assertions.assertNotNull(n3.getId());

        // 未读数应为 3
        long unread3 = notificationService.getUnreadCount(testUserId);
        Assertions.assertEquals(3, unread3);

        // 分页查询未读列表
        NotificationQueryRequest queryReq = new NotificationQueryRequest();
        queryReq.setIsRead(0);
        Page<NotificationVO> unreadPage = notificationService.getUserNotifications(testUserId, queryReq);
        Assertions.assertEquals(3, unreadPage.getTotal());
        Assertions.assertEquals(3, unreadPage.getRecords().size());

        // 标记 n1 为已读
        boolean readSuccess = notificationService.markAsRead(testUserId, n1.getId());
        Assertions.assertTrue(readSuccess);

        // 未读数减为 2
        long unread2 = notificationService.getUnreadCount(testUserId);
        Assertions.assertEquals(2, unread2);

        // 分类过滤查询
        NotificationQueryRequest filterReq = new NotificationQueryRequest();
        filterReq.setCategory("ORDER_DISPATCH");
        Page<NotificationVO> dispatchPage = notificationService.getUserNotifications(testUserId, filterReq);
        Assertions.assertEquals(1, dispatchPage.getTotal());
        Assertions.assertEquals("测试通知2", dispatchPage.getRecords().get(0).getTitle());

        // 一键全部标为已读
        int markedCount = notificationService.markAllAsRead(testUserId);
        Assertions.assertEquals(2, markedCount);

        // 最终未读数应为 0
        long unreadFinal = notificationService.getUnreadCount(testUserId);
        Assertions.assertEquals(0, unreadFinal);
    }

    @Test
    @DisplayName("测试防越权：用户不能标记他人名下的通知为已读")
    @Transactional
    public void testUnauthorizedMarkAsRead() {
        Long userA = 99911L;
        Long userB = 99912L;

        SysNotification notifA = notificationService.sendNotification(
                userA, "用户A的通知", "内容A", "ORDER_FLOW", 2001L);

        // userB 试图标记 userA 的通知
        boolean hacked = notificationService.markAsRead(userB, notifA.getId());
        Assertions.assertFalse(hacked, "他人无法篡改并标记非本人的通知已读状态");

        // userA 依然是未读
        Assertions.assertEquals(1, notificationService.getUnreadCount(userA));
    }

    @Test
    @DisplayName("测试工单完整生命周期流转时间轴不可篡改性与审计追溯完整性")
    public void testWorkOrderAuditTimelineTraceability() throws Exception {
        // 创建测试空间节点与故障类别
        com.campus.repair.modules.spatial.entity.SpatialNode campus = new com.campus.repair.modules.spatial.entity.SpatialNode();
        campus.setName("审计测试校区");
        campus.setLevelType(1);
        campus = spatialNodeService.createNode(campus);

        com.campus.repair.modules.spatial.entity.SpatialNode room = new com.campus.repair.modules.spatial.entity.SpatialNode();
        room.setParentId(campus.getId());
        room.setName("审计实训室101");
        room.setLevelType(4);
        room = spatialNodeService.createNode(room);

        com.campus.repair.modules.spatial.entity.RepairCategory cat = new com.campus.repair.modules.spatial.entity.RepairCategory();
        cat.setName("时间轴审计故障类");
        cat.setLevel(2);
        cat.setWorkerSkillCode("AUDIT_SKILL");
        cat.setSlaHours(24);
        cat = categoryService.createCategory(cat);

        // 创建学生与师傅
        com.campus.repair.modules.user.entity.SysUser student = new com.campus.repair.modules.user.entity.SysUser();
        student.setUsername("student_audit");
        student.setRealName("审计学生");
        student.setPhone("13911112222");
        student.setPassword(passwordEncoder.encode("123456"));
        student.setUserType("USER_STUDENT");
        student.setStatus(1);
        student.setIsFirstLogin(0);
        userMapper.insert(student);

        com.campus.repair.modules.user.entity.SysUser worker = new com.campus.repair.modules.user.entity.SysUser();
        worker.setUsername("worker_audit");
        worker.setRealName("审计师傅");
        worker.setPhone("13911113333");
        worker.setPassword(passwordEncoder.encode("123456"));
        worker.setUserType("REPAIR_WORKER");
        worker.setStatus(1);
        worker.setIsFirstLogin(0);
        userMapper.insert(worker);

        com.campus.repair.modules.user.entity.WorkerProfile profile = new com.campus.repair.modules.user.entity.WorkerProfile();
        profile.setUserId(worker.getId());
        profile.setSkillCode("AUDIT_SKILL");
        profile.setCampusId(campus.getId());
        profile.setMaxLoad(5);
        profile.setCurrentLoad(0);
        profile.setOnDutyStatus(1);
        profile.setRatingAvg(new java.math.BigDecimal("4.9"));
        workerProfileMapper.insert(profile);

        Long studentId = student.getId();
        Long workerId = worker.getId();

        // 1. 提交工单
        WorkOrderSubmitRequest submitReq = new WorkOrderSubmitRequest();
        submitReq.setSpaceId(room.getId());
        submitReq.setCategoryId(cat.getId());
        submitReq.setDescription("水龙头严重损坏漏水，需要尽快处理");
        submitReq.setAppointmentPeriod("周一上午");
        submitReq.setUrgencyLevel(1);
        submitReq.setApplicantPhone(student.getPhone());
        submitReq.setFaultImages(List.of("http://image1.jpg", "http://image2.jpg"));

        WorkOrderSubmitResultVO submitResult = workOrderService.submitOrder(studentId, submitReq);
        Long orderId = submitResult.getOrderId();
        Assertions.assertNotNull(orderId);

        // 等待异步派单稍微沉淀
        Thread.sleep(200);

        // 2. 调度中心人工改派给师傅 workerId
        WorkOrderManualDispatchRequest dispatchReq = new WorkOrderManualDispatchRequest();
        dispatchReq.setWorkerId(workerId);
        dispatchReq.setDispatchRemark("后勤调度员指派资深技师处理");
        workOrderService.manualDispatch(orderId, 1L, dispatchReq);

        // 3. 师傅接单出发
        workOrderService.acceptOrder(orderId, workerId);

        // 4. 师傅挂起工单
        com.campus.repair.modules.workorder.dto.WorkOrderSuspendRequest suspendReq =
                new com.campus.repair.modules.workorder.dto.WorkOrderSuspendRequest();
        suspendReq.setSuspendReasonType("MATERIAL_SHORTAGE");
        suspendReq.setReasonDetail("缺少特种水龙头管件，已向仓库申领");
        suspendReq.setEstimatedResumeTime("明天上午10点");
        workOrderService.suspendOrder(orderId, workerId, suspendReq);

        // 5. 师傅恢复施工
        workOrderService.resumeOrder(orderId, workerId);

        // 6. 师傅完工上报并核销
        com.campus.repair.modules.workorder.dto.WorkOrderFinishRequest finishReq =
                new com.campus.repair.modules.workorder.dto.WorkOrderFinishRequest();
        finishReq.setRepairSummary("已更换全新全铜水龙头，现场通水测试无漏水");
        finishReq.setRepairImages(List.of("http://finish1.jpg"));
        finishReq.setMaterialConsumptions(List.of());
        workOrderService.finishOrder(orderId, workerId, finishReq);

        // 7. 师生评价触发差评返工
        WorkOrderEvaluateRequest reworkReq = new WorkOrderEvaluateRequest();
        reworkReq.setRatingStar(2);
        reworkReq.setComment("水龙头虽然换了但水压依然很小，需要二次排查");
        workOrderService.evaluateOrder(orderId, studentId, reworkReq);

        // 8. 主管仲裁返工（退回原师傅限期整改）
        com.campus.repair.modules.workorder.dto.WorkOrderArbitrateReworkRequest arbitrateReq =
                new com.campus.repair.modules.workorder.dto.WorkOrderArbitrateReworkRequest();
        arbitrateReq.setArbitrationDecision("RETRY_ORIGINAL_WORKER");
        arbitrateReq.setArbitrationNote("已电话联系师傅，排查管路过滤网杂质");
        workOrderService.arbitrateRework(orderId, 1L, arbitrateReq);

        // 9. 查询流转时间轴
        List<RepairOrderLog> timeline = workOrderService.getTimeline(orderId);
        Assertions.assertNotNull(timeline);
        Assertions.assertTrue(timeline.size() >= 7, "时间轴应至少包含 7 步核心审计动作，实际: " + timeline.size());

        // 验证每一步动作的不可篡改记录
        List<String> actions = timeline.stream().map(RepairOrderLog::getActionType).toList();
        Assertions.assertTrue(actions.contains("SUBMIT"), "包含初始提单节点");
        Assertions.assertTrue(actions.contains("MANUAL_DISPATCH"), "包含人工指派节点");
        Assertions.assertTrue(actions.contains("ACCEPT"), "包含接单出发节点");
        Assertions.assertTrue(actions.contains("SUSPEND"), "包含工单挂起节点");
        Assertions.assertTrue(actions.contains("RESUME"), "包含工单恢复施工节点");
        Assertions.assertTrue(actions.contains("FINISH_UPLOAD"), "包含完工填报节点");
        Assertions.assertTrue(actions.contains("REWORK_TRIGGER"), "包含差评返工触发节点");
        Assertions.assertTrue(actions.contains("REWORK_ASSIGN"), "包含主管仲裁返工节点");

        // 验证时间轴按创建时间单调递增
        for (int i = 0; i < timeline.size() - 1; i++) {
            RepairOrderLog cur = timeline.get(i);
            RepairOrderLog next = timeline.get(i + 1);
            Assertions.assertFalse(cur.getCreatedAt().isAfter(next.getCreatedAt()),
                    "审计日志时间必须严格按发生时间升序排列");
            Assertions.assertNotNull(cur.getOperatorRole(), "操作人角色必填不可为空");
            Assertions.assertNotNull(cur.getFromStatus(), "跃迁前状态不可为空");
            Assertions.assertNotNull(cur.getToStatus(), "跃迁后状态不可为空");
        }
    }
}
