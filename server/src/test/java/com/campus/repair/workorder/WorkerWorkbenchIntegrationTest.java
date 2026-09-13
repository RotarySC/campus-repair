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
import com.campus.repair.modules.workorder.dto.WorkOrderManualDispatchRequest;
import com.campus.repair.modules.workorder.dto.WorkOrderSubmitRequest;
import com.campus.repair.modules.workorder.dto.WorkOrderSuspendRequest;
import com.campus.repair.modules.workorder.dto.WorkOrderTransferApplyRequest;
import com.campus.repair.modules.workorder.dto.WorkerOrderListRequest;
import com.campus.repair.modules.workorder.entity.RepairOrder;
import com.campus.repair.modules.workorder.enums.OrderStatusEnum;
import com.campus.repair.modules.workorder.mapper.RepairOrderMapper;
import com.campus.repair.modules.workorder.service.WorkOrderService;
import com.campus.repair.modules.workorder.vo.WorkOrderSubmitResultVO;
import com.campus.repair.modules.workorder.vo.WorkerOrderListResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class WorkerWorkbenchIntegrationTest {

    @Autowired
    private WorkOrderService workOrderService;

    @Autowired
    private RepairOrderMapper orderMapper;

    @Autowired
    private WorkerProfileMapper workerProfileMapper;

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private SpatialNodeService spatialNodeService;

    @Autowired
    private RepairCategoryService categoryService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long testCampusId;
    private Long testRoomId;
    private Long testCategoryId;
    private SysUser workerA;
    private SysUser workerB;

    @BeforeEach
    void setUp() {
        SpatialNode campus = new SpatialNode();
        campus.setName("师傅工作台校区");
        campus.setLevelType(1);
        campus = spatialNodeService.createNode(campus);
        testCampusId = campus.getId();

        SpatialNode room = new SpatialNode();
        room.setParentId(testCampusId);
        room.setName("工科楼401");
        room.setLevelType(4);
        room = spatialNodeService.createNode(room);
        testRoomId = room.getId();

        // 2. 创建故障细类 (注意：设置一个冷门工种 LOCKSMITH，避免触发针对 ELECTRICIAN 师傅的自动派单)
        RepairCategory cat = new RepairCategory();
        cat.setName("门锁把手脱落");
        cat.setLevel(2);
        cat.setWorkerSkillCode("LOCKSMITH");
        cat.setSlaHours(24);
        cat = categoryService.createCategory(cat);
        testCategoryId = cat.getId();

        workerA = createWorker("worker_electric_a", "电工王师傅", "13911110001");
        WorkerProfile pA = new WorkerProfile();
        pA.setUserId(workerA.getId());
        pA.setSkillCode("ELECTRICIAN");
        pA.setCampusId(testCampusId);
        pA.setMaxLoad(5);
        pA.setCurrentLoad(0);
        pA.setOnDutyStatus(1);
        pA.setRatingAvg(new BigDecimal("4.90"));
        workerProfileMapper.insert(pA);

        workerB = createWorker("worker_electric_b", "电工张师傅", "13922220002");
        WorkerProfile pB = new WorkerProfile();
        pB.setUserId(workerB.getId());
        pB.setSkillCode("ELECTRICIAN");
        pB.setCampusId(testCampusId);
        pB.setMaxLoad(5);
        pB.setCurrentLoad(0);
        pB.setOnDutyStatus(1);
        pB.setRatingAvg(new BigDecimal("4.80"));
        workerProfileMapper.insert(pB);
    }

    private SysUser createWorker(String username, String realName, String phone) {
        SysUser u = new SysUser();
        u.setUsername(username);
        u.setRealName(realName);
        u.setPhone(phone);
        u.setPassword(passwordEncoder.encode("InitPass@2026"));
        u.setUserType("LOGISTICS");
        u.setStatus(1);
        u.setIsFirstLogin(0);
        userMapper.insert(u);
        return u;
    }

    @Test
    @DisplayName("师傅工作台三栏分页与数据权限身份隔离测试")
    void testWorkerOrderListIsolation() throws Exception {
        // 创建工单 (注意：提单默认 spaceId = testRoomId，对应工种 ELECTRICIAN，系统会自动触发自动派单)
        // 为了精准测试 workerA 的工作台，我们直接创建待派工单并直接指派给 workerA
        WorkOrderSubmitRequest req1 = new WorkOrderSubmitRequest();
        req1.setSpaceId(testRoomId);
        req1.setCategoryId(testCategoryId);
        req1.setDescription("插座短路冒烟测试");
        req1.setUrgencyLevel(2);
        req1.setAppointmentPeriod("上午08:30-11:30");
        WorkOrderSubmitResultVO res1 = workOrderService.submitOrder(1L, req1);

        Thread.sleep(200);

        // 无论自动派单命中了谁，我们显式强制改派/指派给 workerA
        WorkOrderManualDispatchRequest dispatchReq = new WorkOrderManualDispatchRequest();
        dispatchReq.setWorkerId(workerA.getId());
        dispatchReq.setDispatchRemark("调度中心人工指派给王师傅");
        workOrderService.manualDispatch(res1.getOrderId(), 1L, dispatchReq);

        RepairOrder checkOrder = orderMapper.selectById(res1.getOrderId());
        assertEquals(workerA.getId(), checkOrder.getWorkerId());
        assertEquals(OrderStatusEnum.ASSIGNED.name(), checkOrder.getStatus());

        // workerA 查询自己的工作台列表 (ASSIGNED)
        WorkerOrderListRequest listReq = new WorkerOrderListRequest();
        listReq.setTab("ASSIGNED");
        WorkerOrderListResultVO listA = workOrderService.getWorkerOrders(workerA.getId(), listReq);

        assertNotNull(listA);
        assertTrue(listA.getCounts().getAssignedCount() >= 1, "workerA 的待承接数量至少为1");
        assertTrue(listA.getOrders().getTotal() >= 1, "workerA 的待承接总记录数至少为1");
        assertTrue(listA.getOrders().getRecords().stream().anyMatch(o -> o.getOrderNo().equals(res1.getOrderNo())));

        // workerB 查询自己的工作台列表
        WorkerOrderListResultVO listB = workOrderService.getWorkerOrders(workerB.getId(), listReq);
        assertFalse(listB.getOrders().getRecords().stream().anyMatch(o -> o.getOrderNo().equals(res1.getOrderNo())), "workerB 不应包含 workerA 的工单");
    }

    @Test
    @DisplayName("接单出发与越权操作拦截测试")
    void testAcceptOrderAndAuthorizationGuard() throws Exception {
        WorkOrderSubmitRequest req = new WorkOrderSubmitRequest();
        req.setSpaceId(testRoomId);
        req.setCategoryId(testCategoryId);
        req.setDescription("电闸拉不上去");
        req.setUrgencyLevel(1);
        req.setAppointmentPeriod("下午14:00-17:30");
        WorkOrderSubmitResultVO res = workOrderService.submitOrder(1L, req);

        Thread.sleep(100);
        WorkOrderManualDispatchRequest dispatchReq = new WorkOrderManualDispatchRequest();
        dispatchReq.setWorkerId(workerA.getId());
        workOrderService.manualDispatch(res.getOrderId(), 1L, dispatchReq);

        // 非本单师傅 workerB 尝试接单，应被 403 越权拦截
        org.springframework.security.access.AccessDeniedException ex = assertThrows(
                org.springframework.security.access.AccessDeniedException.class, () ->
                workOrderService.acceptOrder(res.getOrderId(), workerB.getId()));
        assertTrue(ex.getMessage().contains("越权拦截"));

        // 本单师傅 workerA 接单出发
        assertDoesNotThrow(() -> workOrderService.acceptOrder(res.getOrderId(), workerA.getId()));

        RepairOrder order = orderMapper.selectById(res.getOrderId());
        assertEquals(OrderStatusEnum.IN_PROGRESS.name(), order.getStatus());
        assertNotNull(order.getAcceptedTime(), "必须固化接单出发时间戳");

        // 验证其他操作对 workerB 的越权拦截
        WorkOrderSuspendRequest suspendReq = new WorkOrderSuspendRequest();
        suspendReq.setSuspendReasonType("MATERIAL_SHORTAGE");
        suspendReq.setReasonDetail("测试");
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () ->
                workOrderService.suspendOrder(res.getOrderId(), workerB.getId(), suspendReq));

        assertThrows(org.springframework.security.access.AccessDeniedException.class, () ->
                workOrderService.resumeOrder(res.getOrderId(), workerB.getId()));

        WorkOrderTransferApplyRequest transferReq = new WorkOrderTransferApplyRequest();
        transferReq.setTransferReason("测试");
        transferReq.setRecommendSkillCode("PLUMBER");
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () ->
                workOrderService.transferApply(res.getOrderId(), workerB.getId(), transferReq));
    }

    @Test
    @DisplayName("现场挂起与解除挂起：记录挂起原因与SLA时限自动顺延测试")
    void testSuspendAndResumeFlow() throws Exception {
        WorkOrderSubmitRequest req = new WorkOrderSubmitRequest();
        req.setSpaceId(testRoomId);
        req.setCategoryId(testCategoryId);
        req.setDescription("现场需特殊备件更换");
        req.setUrgencyLevel(1);
        req.setAppointmentPeriod("上午08:30-11:30");
        WorkOrderSubmitResultVO res = workOrderService.submitOrder(1L, req);

        Thread.sleep(100);
        WorkOrderManualDispatchRequest dispatchReq = new WorkOrderManualDispatchRequest();
        dispatchReq.setWorkerId(workerA.getId());
        workOrderService.manualDispatch(res.getOrderId(), 1L, dispatchReq);
        workOrderService.acceptOrder(res.getOrderId(), workerA.getId());

        RepairOrder orderBeforeSuspend = orderMapper.selectById(res.getOrderId());
        LocalDateTime slaBefore = orderBeforeSuspend.getSlaDeadline();

        // 1. 申请挂起
        WorkOrderSuspendRequest suspendReq = new WorkOrderSuspendRequest();
        suspendReq.setSuspendReasonType("MATERIAL_SHORTAGE");
        suspendReq.setReasonDetail("现场空气开关规格特殊，库房无备件，需外采");
        suspendReq.setEstimatedResumeTime("2026-09-08 14:00:00");
        assertDoesNotThrow(() -> workOrderService.suspendOrder(res.getOrderId(), workerA.getId(), suspendReq));

        RepairOrder suspendedOrder = orderMapper.selectById(res.getOrderId());
        assertEquals(OrderStatusEnum.SUSPENDED.name(), suspendedOrder.getStatus());
        assertEquals(1, suspendedOrder.getSuspendCount());

        // 2. 解除挂起恢复施工
        Thread.sleep(100);
        assertDoesNotThrow(() -> workOrderService.resumeOrder(res.getOrderId(), workerA.getId()));

        RepairOrder resumedOrder = orderMapper.selectById(res.getOrderId());
        assertEquals(OrderStatusEnum.IN_PROGRESS.name(), resumedOrder.getStatus());
        assertTrue(resumedOrder.getTotalSuspendMinutes() >= 1, "累计挂起分钟数应记录");
        assertTrue(resumedOrder.getSlaDeadline().isAfter(slaBefore), "SLA截止时间应自动顺延");
    }

    @Test
    @DisplayName("申请转派：移出师傅负荷、工单重回人工调度池并推荐新工种")
    void testTransferApplyFlow() throws Exception {
        WorkOrderSubmitRequest req = new WorkOrderSubmitRequest();
        req.setSpaceId(testRoomId);
        req.setCategoryId(testCategoryId);
        req.setDescription("排查发现不仅跳闸，暗管内部漏水导致短路");
        req.setUrgencyLevel(1);
        req.setAppointmentPeriod("上午08:30-11:30");
        WorkOrderSubmitResultVO res = workOrderService.submitOrder(1L, req);

        Thread.sleep(100);
        WorkOrderManualDispatchRequest dispatchReq = new WorkOrderManualDispatchRequest();
        dispatchReq.setWorkerId(workerA.getId());
        workOrderService.manualDispatch(res.getOrderId(), 1L, dispatchReq);
        workOrderService.acceptOrder(res.getOrderId(), workerA.getId());

        // 确认 workerA 此时负荷为 1
        WorkerProfile pA = workerProfileMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkerProfile>()
                        .eq(WorkerProfile::getUserId, workerA.getId()));
        assertEquals(1, pA.getCurrentLoad());

        // workerA 发起转派申请 (推荐水暖工 PLUMBER)
        WorkOrderTransferApplyRequest transferReq = new WorkOrderTransferApplyRequest();
        transferReq.setTransferReason("墙内暗管漏水导致强电跳闸，必须先由水暖工切断水路修复暗管");
        transferReq.setRecommendSkillCode("PLUMBER");
        assertDoesNotThrow(() -> workOrderService.transferApply(res.getOrderId(), workerA.getId(), transferReq));

        RepairOrder transferredOrder = orderMapper.selectById(res.getOrderId());
        assertEquals(OrderStatusEnum.MANUAL_DISPATCH.name(), transferredOrder.getStatus());
        assertEquals("PLUMBER", transferredOrder.getWorkerSkillCode(), "工种应变更为推荐工种");

        // 验证 workerA 负荷已被安全释放回 0
        WorkerProfile pAAfter = workerProfileMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkerProfile>()
                        .eq(WorkerProfile::getUserId, workerA.getId()));
        assertEquals(0, pAAfter.getCurrentLoad());
    }

    @Test
    @DisplayName("挂起态直接转派测试：已挂起工单在现场排查后允许合法申请转派")
    void testTransferApplyFromSuspendedState() throws Exception {
        WorkOrderSubmitRequest req = new WorkOrderSubmitRequest();
        req.setSpaceId(testRoomId);
        req.setCategoryId(testCategoryId);
        req.setDescription("挂起态转派测试");
        req.setUrgencyLevel(1);
        req.setAppointmentPeriod("上午08:30-11:30");
        WorkOrderSubmitResultVO res = workOrderService.submitOrder(1L, req);

        Thread.sleep(100);
        WorkOrderManualDispatchRequest dispatchReq = new WorkOrderManualDispatchRequest();
        dispatchReq.setWorkerId(workerA.getId());
        workOrderService.manualDispatch(res.getOrderId(), 1L, dispatchReq);
        workOrderService.acceptOrder(res.getOrderId(), workerA.getId());

        // 先挂起
        WorkOrderSuspendRequest suspendReq = new WorkOrderSuspendRequest();
        suspendReq.setSuspendReasonType("OUTSOURCING_BLOCK");
        suspendReq.setReasonDetail("现场排查复杂，先挂起协调");
        workOrderService.suspendOrder(res.getOrderId(), workerA.getId(), suspendReq);

        // 挂起状态下直接申请转派
        WorkOrderTransferApplyRequest transferReq = new WorkOrderTransferApplyRequest();
        transferReq.setTransferReason("经与外协共同确认，需由高低压特种电工处理");
        transferReq.setRecommendSkillCode("ELECTRICIAN");
        assertDoesNotThrow(() -> workOrderService.transferApply(res.getOrderId(), workerA.getId(), transferReq));

        RepairOrder transferredOrder = orderMapper.selectById(res.getOrderId());
        assertEquals(OrderStatusEnum.MANUAL_DISPATCH.name(), transferredOrder.getStatus());
    }
}
