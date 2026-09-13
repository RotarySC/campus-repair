package com.campus.repair.workorder;

import com.campus.repair.common.exception.BusinessException;
import com.campus.repair.common.exception.ErrorCode;
import com.campus.repair.modules.spatial.entity.RepairCategory;
import com.campus.repair.modules.spatial.entity.SpatialNode;
import com.campus.repair.modules.spatial.service.RepairCategoryService;
import com.campus.repair.modules.spatial.service.SpatialNodeService;
import com.campus.repair.modules.user.dto.WorkerBoardQueryRequest;
import com.campus.repair.modules.user.entity.SysUser;
import com.campus.repair.modules.user.entity.WorkerProfile;
import com.campus.repair.modules.user.mapper.SysUserMapper;
import com.campus.repair.modules.user.mapper.WorkerProfileMapper;
import com.campus.repair.modules.user.service.WorkerService;
import com.campus.repair.modules.user.vo.WorkerBoardVO;
import com.campus.repair.modules.workorder.dto.DispatchPoolQueryRequest;
import com.campus.repair.modules.workorder.dto.WorkOrderCancelRequest;
import com.campus.repair.modules.workorder.dto.WorkOrderManualDispatchRequest;
import com.campus.repair.modules.workorder.dto.WorkOrderSubmitRequest;
import com.campus.repair.modules.workorder.entity.RepairOrder;
import com.campus.repair.modules.workorder.enums.OrderStatusEnum;
import com.campus.repair.modules.workorder.mapper.RepairOrderMapper;
import com.campus.repair.modules.workorder.service.WorkOrderService;
import com.campus.repair.modules.workorder.vo.DispatchPoolResultVO;
import com.campus.repair.modules.workorder.vo.WorkOrderSubmitResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class DispatchConsoleIntegrationTest {

    @Autowired
    private WorkOrderService workOrderService;

    @Autowired
    private WorkerService workerService;

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
    private SysUser testWorker1;
    private SysUser testWorker2;

    @BeforeEach
    void setUp() {
        SpatialNode campus = new SpatialNode();
        campus.setName("调度中心测试校区");
        campus.setLevelType(1);
        campus = spatialNodeService.createNode(campus);
        testCampusId = campus.getId();

        SpatialNode room = new SpatialNode();
        room.setParentId(testCampusId);
        room.setName("调度室101");
        room.setLevelType(4);
        room = spatialNodeService.createNode(room);
        testRoomId = room.getId();

        RepairCategory cat = new RepairCategory();
        cat.setName("调度专用水暖故障");
        cat.setLevel(2);
        cat.setWorkerSkillCode("PLUMBER");
        cat.setSlaHours(12);
        cat = categoryService.createCategory(cat);
        testCategoryId = cat.getId();

        testWorker1 = createTestWorker("plumber_one", "水暖工王师傅", "13811112222");
        WorkerProfile p1 = new WorkerProfile();
        p1.setUserId(testWorker1.getId());
        p1.setSkillCode("PLUMBER");
        p1.setCampusId(testCampusId);
        p1.setMaxLoad(3);
        p1.setCurrentLoad(1);
        p1.setOnDutyStatus(1);
        p1.setRatingAvg(new BigDecimal("4.90"));
        workerProfileMapper.insert(p1);

        testWorker2 = createTestWorker("plumber_two", "水暖工赵师傅", "13833334444");
        WorkerProfile p2 = new WorkerProfile();
        p2.setUserId(testWorker2.getId());
        p2.setSkillCode("PLUMBER");
        p2.setCampusId(testCampusId);
        p2.setMaxLoad(3);
        p2.setCurrentLoad(3); // 已满载
        p2.setOnDutyStatus(1);
        p2.setRatingAvg(new BigDecimal("4.85"));
        workerProfileMapper.insert(p2);
    }

    private SysUser createTestWorker(String username, String realName, String phone) {
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
    @DisplayName("调度中心待办工单池多维查询与分类统计测试")
    void testDispatchPoolQueryAndCounts() throws Exception {
        // 创建一条加急待人工调度工单
        WorkOrderSubmitRequest req1 = new WorkOrderSubmitRequest();
        req1.setSpaceId(testRoomId);
        req1.setCategoryId(testCategoryId);
        req1.setDescription("主管道严重漏水已淹没地面");
        req1.setUrgencyLevel(2); // 加急单
        req1.setAppointmentPeriod("即刻上门");
        WorkOrderSubmitResultVO res1 = workOrderService.submitOrder(1L, req1);

        Thread.sleep(100); // 稍微等待异步提单事件处理
        RepairOrder order1 = orderMapper.selectById(res1.getOrderId());
        order1.setStatus(OrderStatusEnum.MANUAL_DISPATCH.name());
        order1.setDispatchType("MANUAL");
        order1.setWorkerId(null);
        orderMapper.updateById(order1);

        // 创建一条返工待派单工单
        WorkOrderSubmitRequest req2 = new WorkOrderSubmitRequest();
        req2.setSpaceId(testRoomId);
        req2.setCategoryId(testCategoryId);
        req2.setDescription("水龙头未拧紧二次滴漏");
        req2.setUrgencyLevel(1);
        req2.setAppointmentPeriod("下午14:00-17:30");
        WorkOrderSubmitResultVO res2 = workOrderService.submitOrder(1L, req2);

        Thread.sleep(100);
        RepairOrder order2 = orderMapper.selectById(res2.getOrderId());
        order2.setStatus(OrderStatusEnum.REWORK_PENDING.name());
        order2.setReworkCount(1);
        orderMapper.updateById(order2);

        // 调度池检索
        DispatchPoolQueryRequest poolReq = new DispatchPoolQueryRequest();
        poolReq.setCategoryFilter("ALL");
        DispatchPoolResultVO poolRes = workOrderService.getDispatchPool(poolReq);

        assertNotNull(poolRes);
        assertNotNull(poolRes.getCounts());
        assertTrue(poolRes.getCounts().getAllCount() >= 2);
        assertTrue(poolRes.getCounts().getUrgentCount() >= 1);
        assertTrue(poolRes.getCounts().getReworkCount() >= 1);

        // 仅筛选返工单
        poolReq.setCategoryFilter("REWORK");
        DispatchPoolResultVO reworkRes = workOrderService.getDispatchPool(poolReq);
        assertTrue(reworkRes.getOrders().getRecords().stream().allMatch(o -> "REWORK_PENDING".equals(o.getStatus())));
    }

    @Test
    @DisplayName("师傅看板负荷实时视图计算测试 (彩色进度条数据就绪)")
    void testWorkerBoardQuery() {
        WorkerBoardQueryRequest req = new WorkerBoardQueryRequest();
        req.setSkillCode("PLUMBER");
        req.setCampusId(testCampusId);

        List<WorkerBoardVO> boardList = workerService.getWorkerBoardList(req);
        assertFalse(boardList.isEmpty());

        WorkerBoardVO w1 = boardList.stream().filter(w -> w.getUserId().equals(testWorker1.getId())).findFirst().orElseThrow();
        assertEquals(1, w1.getCurrentLoad());
        assertEquals(3, w1.getMaxLoad());
        assertEquals(33, w1.getLoadRate()); // 1/3 ≈ 33%
        assertFalse(w1.getIsOverloaded());

        WorkerBoardVO w2 = boardList.stream().filter(w -> w.getUserId().equals(testWorker2.getId())).findFirst().orElseThrow();
        assertEquals(3, w2.getCurrentLoad());
        assertEquals(3, w2.getMaxLoad());
        assertEquals(100, w2.getLoadRate());
        assertTrue(w2.getIsOverloaded());
    }

    @Test
    @DisplayName("人工指派与改派流转：负荷原子增减与状态机跃迁校验")
    void testManualDispatchAndReassign() {
        // 1. 提交工单并置为待人工调度
        RepairCategory catNoAuto = new RepairCategory();
        catNoAuto.setName("非自动派工单");
        catNoAuto.setLevel(2);
        catNoAuto.setWorkerSkillCode("MANUAL_ONLY_SKILL");
        catNoAuto.setSlaHours(12);
        catNoAuto = categoryService.createCategory(catNoAuto);

        WorkOrderSubmitRequest req = new WorkOrderSubmitRequest();
        req.setSpaceId(testRoomId);
        req.setCategoryId(catNoAuto.getId());
        req.setDescription("洗手池水阀损坏");
        req.setUrgencyLevel(1);
        req.setAppointmentPeriod("上午08:30-11:30");
        WorkOrderSubmitResultVO res = workOrderService.submitOrder(1L, req);

        try {
            Thread.sleep(100);
        } catch (InterruptedException ignored) {}

        RepairOrder order = orderMapper.selectById(res.getOrderId());
        order.setStatus(OrderStatusEnum.MANUAL_DISPATCH.name());
        orderMapper.updateById(order);

        // 2. 指派给已满载的 testWorker2，应被阻断抛出 WORKER_LOAD_EXCEEDED
        WorkOrderManualDispatchRequest dispatchReqFail = new WorkOrderManualDispatchRequest();
        dispatchReqFail.setWorkerId(testWorker2.getId());
        dispatchReqFail.setDispatchRemark("强行指派满载师傅");
        BusinessException ex = assertThrows(BusinessException.class, () ->
                workOrderService.manualDispatch(order.getId(), 1L, dispatchReqFail));
        assertEquals(ErrorCode.WORKER_LOAD_EXCEEDED.getCode(), ex.getCode());

        // 3. 成功指派给未满载的 testWorker1
        WorkOrderManualDispatchRequest dispatchReqSuccess = new WorkOrderManualDispatchRequest();
        dispatchReqSuccess.setWorkerId(testWorker1.getId());
        dispatchReqSuccess.setDispatchRemark("指派给王师傅优先处理");
        assertDoesNotThrow(() -> workOrderService.manualDispatch(order.getId(), 1L, dispatchReqSuccess));

        RepairOrder updatedOrder = orderMapper.selectById(order.getId());
        assertEquals(OrderStatusEnum.ASSIGNED.name(), updatedOrder.getStatus());
        assertEquals(testWorker1.getId(), updatedOrder.getWorkerId());
        assertEquals("水暖工王师傅", updatedOrder.getWorkerName());

        // 王师傅负荷应该从 1 增加到 2
        WorkerProfile p1Updated = workerProfileMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkerProfile>()
                        .eq(WorkerProfile::getUserId, testWorker1.getId()));
        assertEquals(2, p1Updated.getCurrentLoad());

        // 4. 改派测试：将工单从王师傅改派给新师傅（先给赵师傅腾出1个名额）
        workerProfileMapper.decrementCurrentLoad(testWorker2.getId()); // 赵师傅负荷 3 -> 2
        WorkOrderManualDispatchRequest reassignReq = new WorkOrderManualDispatchRequest();
        reassignReq.setWorkerId(testWorker2.getId());
        reassignReq.setDispatchRemark("王师傅外出，改派给赵师傅");
        assertDoesNotThrow(() -> workOrderService.manualDispatch(order.getId(), 1L, reassignReq));

        RepairOrder reassignedOrder = orderMapper.selectById(order.getId());
        assertEquals(testWorker2.getId(), reassignedOrder.getWorkerId());
        assertEquals(OrderStatusEnum.ASSIGNED.name(), reassignedOrder.getStatus());

        // 验证王师傅负荷减回到 1，赵师傅负荷增回到 3
        WorkerProfile p1AfterReassign = workerProfileMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkerProfile>()
                        .eq(WorkerProfile::getUserId, testWorker1.getId()));
        assertEquals(1, p1AfterReassign.getCurrentLoad());
        WorkerProfile p2AfterReassign = workerProfileMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkerProfile>()
                        .eq(WorkerProfile::getUserId, testWorker2.getId()));
        assertEquals(3, p2AfterReassign.getCurrentLoad());
    }

    @Test
    @DisplayName("虚假工单作废：必须录入原因，流转为 CANCELLED 且不可逆")
    void testCancelOrder() throws Exception {
        WorkOrderSubmitRequest req = new WorkOrderSubmitRequest();
        req.setSpaceId(testRoomId);
        req.setCategoryId(testCategoryId);
        req.setDescription("恶作剧报修");
        req.setUrgencyLevel(1);
        req.setAppointmentPeriod("上午08:30-11:30");
        WorkOrderSubmitResultVO res = workOrderService.submitOrder(1L, req);

        Thread.sleep(100);
        RepairOrder order = orderMapper.selectById(res.getOrderId());
        order.setStatus(OrderStatusEnum.MANUAL_DISPATCH.name());
        orderMapper.updateById(order);

        // 作废工单
        WorkOrderCancelRequest cancelReq = new WorkOrderCancelRequest();
        cancelReq.setCancelReason("电话核实为学生误触测试，无需现场维修，予以作废");
        assertDoesNotThrow(() -> workOrderService.cancelOrder(order.getId(), 1L, cancelReq));

        RepairOrder cancelledOrder = orderMapper.selectById(order.getId());
        assertEquals(OrderStatusEnum.CANCELLED.name(), cancelledOrder.getStatus());
        assertEquals("电话核实为学生误触测试，无需现场维修，予以作废", cancelledOrder.getCancelReason());

        // 状态机验证：CANCELLED 是终态，不允许再次指派
        WorkOrderManualDispatchRequest dispatchReq = new WorkOrderManualDispatchRequest();
        dispatchReq.setWorkerId(testWorker1.getId());
        assertThrows(BusinessException.class, () ->
                workOrderService.manualDispatch(order.getId(), 1L, dispatchReq));
    }

    @Test
    @DisplayName("返工单核实作废：REWORK_PENDING 状态允许核实作废且不二次扣减师傅负荷")
    void testCancelReworkOrder() throws Exception {
        WorkOrderSubmitRequest req = new WorkOrderSubmitRequest();
        req.setSpaceId(testRoomId);
        req.setCategoryId(testCategoryId);
        req.setDescription("返工单作废测试");
        req.setUrgencyLevel(1);
        req.setAppointmentPeriod("上午08:30-11:30");
        WorkOrderSubmitResultVO res = workOrderService.submitOrder(1L, req);

        Thread.sleep(100);
        RepairOrder order = orderMapper.selectById(res.getOrderId());
        order.setStatus(OrderStatusEnum.REWORK_PENDING.name());
        order.setWorkerId(testWorker1.getId()); // 记录此前师傅ID
        orderMapper.updateById(order);

        int originalLoad = workerProfileMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkerProfile>()
                        .eq(WorkerProfile::getUserId, testWorker1.getId())).getCurrentLoad();

        WorkOrderCancelRequest cancelReq = new WorkOrderCancelRequest();
        cancelReq.setCancelReason("师生表示故障已自行恢复，撤回返工单");
        assertDoesNotThrow(() -> workOrderService.cancelOrder(order.getId(), 1L, cancelReq));

        RepairOrder cancelledOrder = orderMapper.selectById(order.getId());
        assertEquals(OrderStatusEnum.CANCELLED.name(), cancelledOrder.getStatus());

        // 师傅负荷不应该被二次重复扣减
        int currentLoad = workerProfileMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkerProfile>()
                        .eq(WorkerProfile::getUserId, testWorker1.getId())).getCurrentLoad();
        assertEquals(originalLoad, currentLoad);
    }
}
