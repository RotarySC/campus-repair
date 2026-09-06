package com.campus.repair.workorder;

import com.campus.repair.modules.spatial.entity.RepairCategory;
import com.campus.repair.modules.spatial.entity.SpatialNode;
import com.campus.repair.modules.spatial.service.RepairCategoryService;
import com.campus.repair.modules.spatial.service.SpatialNodeService;
import com.campus.repair.modules.user.entity.SysUser;
import com.campus.repair.modules.user.entity.WorkerProfile;
import com.campus.repair.modules.user.mapper.SysUserMapper;
import com.campus.repair.modules.user.mapper.WorkerProfileMapper;
import com.campus.repair.modules.workorder.dto.WorkOrderSubmitRequest;
import com.campus.repair.modules.workorder.entity.RepairOrder;
import com.campus.repair.modules.workorder.enums.OrderStatusEnum;
import com.campus.repair.modules.workorder.mapper.RepairOrderMapper;
import com.campus.repair.modules.workorder.service.DispatchEngine;
import com.campus.repair.modules.workorder.service.WorkOrderService;
import com.campus.repair.modules.workorder.service.WorkOrderStateMachine;
import com.campus.repair.modules.workorder.vo.WorkOrderSubmitResultVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class DispatchEngineIntegrationTest {

    @Autowired
    private DispatchEngine dispatchEngine;

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
    private WorkOrderStateMachine stateMachine;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("状态机守卫严格验证：合法流转放行，非法跃迁拦截抛异常")
    void testStateMachineTransitions() {
        // 合法跃迁测试
        assertDoesNotThrow(() -> stateMachine.verifyTransition(OrderStatusEnum.DRAFT, OrderStatusEnum.PENDING_DISPATCH));
        assertDoesNotThrow(() -> stateMachine.verifyTransition(OrderStatusEnum.PENDING_DISPATCH, OrderStatusEnum.ASSIGNED));
        assertDoesNotThrow(() -> stateMachine.verifyTransition(OrderStatusEnum.ASSIGNED, OrderStatusEnum.IN_PROGRESS));
        assertDoesNotThrow(() -> stateMachine.verifyTransition(OrderStatusEnum.IN_PROGRESS, OrderStatusEnum.PENDING_ACCEPTANCE));
        assertDoesNotThrow(() -> stateMachine.verifyTransition(OrderStatusEnum.PENDING_ACCEPTANCE, OrderStatusEnum.COMPLETED));

        // 非法跃迁拦截 (如草稿直接结单，或待派单直接变维修中)
        assertThrows(RuntimeException.class, () -> stateMachine.verifyTransition(OrderStatusEnum.DRAFT, OrderStatusEnum.COMPLETED));
        assertThrows(RuntimeException.class, () -> stateMachine.verifyTransition(OrderStatusEnum.PENDING_DISPATCH, OrderStatusEnum.IN_PROGRESS));
    }

    @Test
    @DisplayName("智能派单匹配：负荷最小优先指派，全员满载时优雅降级为待人工调度")
    void testAutoDispatchAndDegrade() {
        // 1. 创建校区与空间
        SpatialNode campus = new SpatialNode();
        campus.setName("智能派单测试校区");
        campus.setLevelType(1);
        campus = spatialNodeService.createNode(campus);
        Long campusId = campus.getId();

        SpatialNode room = new SpatialNode();
        room.setParentId(campusId);
        room.setName("实训室201");
        room.setLevelType(4);
        room = spatialNodeService.createNode(room);

        // 2. 创建故障细类 (电工工种 ELECTRICIAN)
        RepairCategory cat = new RepairCategory();
        cat.setName("空气开关冒火花");
        cat.setLevel(2);
        cat.setWorkerSkillCode("ELECTRICIAN");
        cat.setSlaHours(8);
        cat = categoryService.createCategory(cat);

        // 3. 准备两位电工师傅：师傅A (在修负荷 3), 师傅B (在修负荷 1)
        SysUser userA = createTestWorker("electrician_a", "电工老张", "13700000011");
        WorkerProfile profileA = new WorkerProfile();
        profileA.setUserId(userA.getId());
        profileA.setSkillCode("ELECTRICIAN");
        profileA.setCampusId(campusId);
        profileA.setMaxLoad(5);
        profileA.setCurrentLoad(3);
        profileA.setOnDutyStatus(1);
        profileA.setRatingAvg(new BigDecimal("4.80"));
        workerProfileMapper.insert(profileA);

        SysUser userB = createTestWorker("electrician_b", "电工小李", "13700000012");
        WorkerProfile profileB = new WorkerProfile();
        profileB.setUserId(userB.getId());
        profileB.setSkillCode("ELECTRICIAN");
        profileB.setCampusId(campusId);
        profileB.setMaxLoad(5);
        profileB.setCurrentLoad(1); // 负荷更低！
        profileB.setOnDutyStatus(1);
        profileB.setRatingAvg(new BigDecimal("4.95"));
        workerProfileMapper.insert(profileB);

        // 4. 提交工单
        WorkOrderSubmitRequest req = new WorkOrderSubmitRequest();
        req.setSpaceId(room.getId());
        req.setCategoryId(cat.getId());
        req.setDescription("实训室强电插座冒火花");
        req.setAppointmentPeriod("上午08:30-11:30");
        WorkOrderSubmitResultVO submitRes = workOrderService.submitOrder(1L, req);

        // 5. 执行智能派单
        boolean dispatched = dispatchEngine.executeAutoDispatch(submitRes.getOrderId());
        assertTrue(dispatched, "自动派单应该成功匹配师傅");

        RepairOrder dispatchedOrder = orderMapper.selectById(submitRes.getOrderId());
        assertEquals(OrderStatusEnum.ASSIGNED.name(), dispatchedOrder.getStatus());
        assertEquals(userB.getId(), dispatchedOrder.getWorkerId(), "应该优先指派在修负荷更低的电工小李");

        // 6. 测试全员满载降级 MANUAL_DISPATCH
        profileA.setCurrentLoad(5);
        workerProfileMapper.updateById(profileA);
        profileB.setCurrentLoad(5);
        workerProfileMapper.updateById(profileB);

        // 再次提交新工单
        req.setDescription("实训室照明灯管不亮");
        WorkOrderSubmitResultVO submitRes2 = workOrderService.submitOrder(1L, req);
        boolean dispatched2 = dispatchEngine.executeAutoDispatch(submitRes2.getOrderId());
        assertFalse(dispatched2, "全员满载时自动派单应返回 false 并降级");

        RepairOrder degradedOrder = orderMapper.selectById(submitRes2.getOrderId());
        assertEquals(OrderStatusEnum.MANUAL_DISPATCH.name(), degradedOrder.getStatus());
        assertEquals("MANUAL", degradedOrder.getDispatchType());
    }

    @Test
    @DisplayName("高并发多线程抢单测试：师傅容量硬约束，杜绝并发超额派单")
    void testConcurrentDispatchSafety() throws Exception {
        SpatialNode campus = new SpatialNode();
        campus.setName("并发校区");
        campus.setLevelType(1);
        campus = spatialNodeService.createNode(campus);

        SpatialNode room = new SpatialNode();
        room.setParentId(campus.getId());
        room.setName("机房301");
        room.setLevelType(4);
        room = spatialNodeService.createNode(room);

        RepairCategory cat = new RepairCategory();
        cat.setName("网络模块故障");
        cat.setLevel(2);
        cat.setWorkerSkillCode("NETWORK");
        cat = categoryService.createCategory(cat);

        // 创建一位网络师傅，容量上限 maxLoad = 3
        SysUser worker = createTestWorker("net_worker", "网络刘师傅", "13700000099");
        WorkerProfile profile = new WorkerProfile();
        profile.setUserId(worker.getId());
        profile.setSkillCode("NETWORK");
        profile.setCampusId(campus.getId());
        profile.setMaxLoad(3);
        profile.setCurrentLoad(0);
        profile.setOnDutyStatus(1);
        workerProfileMapper.insert(profile);

        // 准备 10 张待派工单
        int totalOrders = 10;
        Long[] orderIds = new Long[totalOrders];
        for (int i = 0; i < totalOrders; i++) {
            WorkOrderSubmitRequest req = new WorkOrderSubmitRequest();
            req.setSpaceId(room.getId());
            req.setCategoryId(cat.getId());
            req.setDescription("网络端口断线测试 " + i);
            req.setAppointmentPeriod("下午14:00-17:30");
            WorkOrderSubmitResultVO res = workOrderService.submitOrder(1L, req);
            orderIds[i] = res.getOrderId();
        }

        // 10 线程并发执行派单
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(totalOrders);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger degradedCount = new AtomicInteger(0);

        for (int i = 0; i < totalOrders; i++) {
            final Long oid = orderIds[i];
            executor.submit(() -> {
                try {
                    boolean ok = dispatchEngine.executeAutoDispatch(oid);
                    if (ok) successCount.incrementAndGet();
                    else degradedCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // 验证：成功指派单数严格等于 maxLoad = 3，其余 7 单安全降级
        assertEquals(3, successCount.get(), "成功指派单数受 maxLoad=3 约束");
        assertEquals(7, degradedCount.get(), "其余工单必须安全降级为待人工调度");
    }

    private SysUser createTestWorker(String username, String realName, String phone) {
        SysUser u = new SysUser();
        u.setUsername(username);
        u.setRealName(realName);
        u.setPhone(phone);
        u.setPassword(passwordEncoder.encode("InitPass@2026"));
        u.setUserType("REPAIR_WORKER");
        u.setStatus(1);
        u.setIsFirstLogin(0);
        userMapper.insert(u);
        return u;
    }
}
