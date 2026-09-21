package com.campus.repair.inventory;

import com.campus.repair.common.exception.BusinessException;
import com.campus.repair.common.exception.ErrorCode;
import com.campus.repair.modules.inventory.dto.MaterialConsumeDTO;
import com.campus.repair.modules.inventory.dto.SparePartQueryRequest;
import com.campus.repair.modules.inventory.entity.RepairMaterialConsume;
import com.campus.repair.modules.inventory.entity.SparePart;
import com.campus.repair.modules.inventory.mapper.RepairMaterialConsumeMapper;
import com.campus.repair.modules.inventory.mapper.SparePartMapper;
import com.campus.repair.modules.inventory.service.InventoryService;
import com.campus.repair.modules.inventory.vo.SparePartVO;
import com.campus.repair.modules.spatial.entity.RepairCategory;
import com.campus.repair.modules.spatial.entity.SpatialNode;
import com.campus.repair.modules.spatial.service.RepairCategoryService;
import com.campus.repair.modules.spatial.service.SpatialNodeService;
import com.campus.repair.modules.user.entity.SysUser;
import com.campus.repair.modules.user.entity.WorkerProfile;
import com.campus.repair.modules.user.mapper.SysUserMapper;
import com.campus.repair.modules.user.mapper.WorkerProfileMapper;
import com.campus.repair.modules.workorder.dto.WorkOrderFinishRequest;
import com.campus.repair.modules.workorder.dto.WorkOrderManualDispatchRequest;
import com.campus.repair.modules.workorder.dto.WorkOrderSubmitRequest;
import com.campus.repair.modules.workorder.entity.RepairOrder;
import com.campus.repair.modules.workorder.entity.RepairOrderEvaluation;
import com.campus.repair.modules.workorder.entity.RepairOrderLog;
import com.campus.repair.modules.workorder.enums.OrderStatusEnum;
import com.campus.repair.modules.workorder.mapper.RepairOrderEvaluationMapper;
import com.campus.repair.modules.workorder.mapper.RepairOrderMapper;
import com.campus.repair.modules.workorder.service.WorkOrderLogService;
import com.campus.repair.modules.workorder.service.WorkOrderService;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class InventoryAndFinishIntegrationTest {

    @Autowired
    private WorkOrderService workOrderService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private SparePartMapper sparePartMapper;

    @Autowired
    private RepairMaterialConsumeMapper consumeMapper;

    @Autowired
    private RepairOrderEvaluationMapper evaluationMapper;

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
    private WorkOrderLogService logService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long testCampusId;
    private Long testRoomId;
    private Long testCategoryId;
    private SysUser testStudent;
    private SysUser worker1;
    private SysUser worker2;
    private SparePart partValve;
    private SparePart partTape;

    @BeforeEach
    void setUp() {
        SpatialNode campus = new SpatialNode();
        campus.setName("耗材测试校区");
        campus.setLevelType(1);
        campus = spatialNodeService.createNode(campus);
        testCampusId = campus.getId();

        SpatialNode room = new SpatialNode();
        room.setParentId(testCampusId);
        room.setName("物料实训楼101");
        room.setLevelType(4);
        room = spatialNodeService.createNode(room);
        testRoomId = room.getId();

        RepairCategory cat = new RepairCategory();
        cat.setName("水暖管路爆裂");
        cat.setLevel(2);
        cat.setWorkerSkillCode("NO_SUCH_SKILL"); // 确保不匹配自动派单
        cat.setSlaHours(24);
        cat = categoryService.createCategory(cat);
        testCategoryId = cat.getId();

        testStudent = createStudent("inv_student_01", "耗材测试同学", "13812340099");

        worker1 = createWorker("inv_worker_01", "水暖张师傅", "13812340001");        WorkerProfile p1 = new WorkerProfile();
        p1.setUserId(worker1.getId());
        p1.setSkillCode("GENERAL");
        p1.setCampusId(testCampusId);
        p1.setMaxLoad(5);
        p1.setCurrentLoad(0);
        p1.setOnDutyStatus(1);
        p1.setRatingAvg(new BigDecimal("4.95"));
        workerProfileMapper.insert(p1);

        worker2 = createWorker("inv_worker_02", "水暖李师傅", "13812340002");
        WorkerProfile p2 = new WorkerProfile();
        p2.setUserId(worker2.getId());
        p2.setSkillCode("GENERAL");
        p2.setCampusId(testCampusId);
        p2.setMaxLoad(5);
        p2.setCurrentLoad(0);
        p2.setOnDutyStatus(1);
        p2.setRatingAvg(new BigDecimal("4.80"));
        workerProfileMapper.insert(p2);

        // 插入测试耗材物料
        partValve = new SparePart();
        partValve.setPartCode("MAT-VALVE-01");
        partValve.setPartName("PPR防爆角阀20mm");
        partValve.setCategoryType("水暖管件");
        partValve.setSpecification("DN20 4分");
        partValve.setUnit("个");
        partValve.setGuidePrice(new BigDecimal("18.50"));
        partValve.setStockQuantity(10);
        partValve.setSafetyStockThreshold(5);
        partValve.setStatus(1);
        sparePartMapper.insert(partValve);

        partTape = new SparePart();
        partTape.setPartCode("MAT-TAPE-01");
        partTape.setPartName("加厚生料带");
        partTape.setCategoryType("水暖管件");
        partTape.setSpecification("15m*18mm*0.1mm");
        partTape.setUnit("卷");
        partTape.setGuidePrice(new BigDecimal("2.50"));
        partTape.setStockQuantity(50);
        partTape.setSafetyStockThreshold(10);
        partTape.setStatus(1);
        sparePartMapper.insert(partTape);
    }

    private SysUser createWorker(String username, String realName, String phone) {
        SysUser u = new SysUser();
        u.setUsername(username);
        u.setRealName(realName);
        u.setPhone(phone);
        u.setPassword(passwordEncoder.encode("password"));
        u.setUserType("LOGISTICS");
        u.setStatus(1);
        u.setIsFirstLogin(0);
        userMapper.insert(u);
        return u;
    }

    /**
     * 本类**自带**一个报修人，而不是复用种子里的 student01。
     *
     * <p>原因（实测踩到的跨类污染）：本类没有 {@code @Transactional}（要验异步派单与
     * 真实事务提交），提交的工单会**留在库里**。此前它写死 {@code submitOrder(3L, …)}，
     * 于是 student01 名下多出 2 张 IN_PROGRESS + 1 张 PENDING_ACCEPTANCE 的残留单；
     * 而 {@code PersonalCenterIntegrationTest#testRepairSummaryBuckets} 对 student01 的
     * 分桶是**精确计数**，一旦 surefire 的文件枚举顺序/耗时变化（例如新增测试类）就会
     * 报 {@code processingCount expected 2 but was 4}。
     *
     * <p>同一个约定在 `AnalyticsCockpitIntegrationTest`（自建大屏测试师生）与
     * `PersonalCenterIntegrationTest`（`OTHER_STUDENT_ID = 99L`）里已经用过：
     * **会提交数据的测试类不要借用别人在断言的账号**。
     */
    private SysUser createStudent(String username, String realName, String phone) {
        SysUser u = new SysUser();
        u.setUsername(username);
        u.setRealName(realName);
        u.setPhone(phone);
        u.setPassword(passwordEncoder.encode("password"));
        u.setUserType("STUDENT");
        u.setStatus(1);
        u.setIsFirstLogin(0);
        userMapper.insert(u);
        return u;
    }

    private RepairOrder createAssignedAndAcceptedOrder(SysUser worker) {
        WorkOrderSubmitRequest sub = new WorkOrderSubmitRequest();
        sub.setSpaceId(testRoomId);
        sub.setCategoryId(testCategoryId);
        sub.setDescription("洗手池水管角阀漏水测试" + System.currentTimeMillis());
        sub.setUrgencyLevel(1);
        sub.setAppointmentPeriod("工作日上午08:30-11:30");
        sub.setApplicantPhone(testStudent.getPhone());

        WorkOrderSubmitResultVO subRes = workOrderService.submitOrder(testStudent.getId(), sub);
        Long orderId = subRes.getOrderId();

        // 稍微休眠 100ms 确保异步事件已降级入池
        try {
            Thread.sleep(100);
        } catch (InterruptedException ignored) {}

        // 调度中心指派给 worker
        WorkOrderManualDispatchRequest dispatchReq = new WorkOrderManualDispatchRequest();
        dispatchReq.setWorkerId(worker.getId());
        dispatchReq.setDispatchRemark("安排现场上门");
        workOrderService.manualDispatch(orderId, 1L, dispatchReq);

        // worker 接单出发 -> IN_PROGRESS
        workOrderService.acceptOrder(orderId, worker.getId());
        return orderMapper.selectById(orderId);
    }

    @Test
    @DisplayName("完工填报与耗材原子核销成功：库存正确扣减、材料费计算、状态流转至待验收并释放师傅负荷")
    void testFinishOrderWithMaterialSuccess() {
        RepairOrder order = createAssignedAndAcceptedOrder(worker1);

        WorkerProfile wpBefore = workerProfileMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkerProfile>()
                        .eq(WorkerProfile::getUserId, worker1.getId())
        );
        assertEquals(1, wpBefore.getCurrentLoad(), "接单后师傅负荷应为 1");

        // 师傅提交完工：消耗 2 个角阀 + 3 卷生料带
        WorkOrderFinishRequest finishReq = new WorkOrderFinishRequest();
        finishReq.setRepairSummary("现场拆卸老化碎裂角阀，重新缠绕生料带安装新防爆角阀，闭水打压测试5分钟无泄漏");
        finishReq.setRepairImages(List.of(
                "http://test.com/finish_01_watermarked.jpg",
                "http://test.com/finish_02_watermarked.jpg"
        ));
        finishReq.setMaterialConsumptions(List.of(
                new MaterialConsumeDTO(partValve.getId(), 2),
                new MaterialConsumeDTO(partTape.getId(), 3)
        ));

        workOrderService.finishOrder(order.getId(), worker1.getId(), finishReq);

        // 1. 验证工单状态和材料成本
        RepairOrder updated = orderMapper.selectById(order.getId());
        assertEquals(OrderStatusEnum.PENDING_ACCEPTANCE.name(), updated.getStatus());
        assertNotNull(updated.getFinishTime(), "完工时间戳应已记录");

        // 成本 = 2 * 18.50 + 3 * 2.50 = 37.00 + 7.50 = 44.50
        BigDecimal expectedCost = new BigDecimal("44.50");
        assertEquals(0, expectedCost.compareTo(updated.getMaterialCost()));

        // 2. 验证库存扣减
        SparePart valveAfter = sparePartMapper.selectById(partValve.getId());
        assertEquals(8, valveAfter.getStockQuantity(), "角阀库存应从 10 扣减至 8");

        SparePart tapeAfter = sparePartMapper.selectById(partTape.getId());
        assertEquals(47, tapeAfter.getStockQuantity(), "生料带库存应从 50 扣减至 47");

        // 3. 验证核销流水明细
        List<RepairMaterialConsume> consumptions = inventoryService.getConsumptionsByOrderId(order.getId());
        assertEquals(2, consumptions.size());
        RepairMaterialConsume c1 = consumptions.get(0);
        assertEquals(partValve.getId(), c1.getPartId());
        assertEquals(2, c1.getConsumeQuantity());
        assertEquals(0, new BigDecimal("18.50").compareTo(c1.getUnitPrice()));
        assertEquals(0, new BigDecimal("37.00").compareTo(c1.getTotalCost()));

        // 4. 验证完工详情记录
        RepairOrderEvaluation eval = evaluationMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RepairOrderEvaluation>()
                        .eq(RepairOrderEvaluation::getOrderId, order.getId())
        );
        assertNotNull(eval);
        assertEquals(order.getOrderNo(), eval.getOrderNo());
        assertTrue(eval.getRepairSummary().contains("闭水打压测试"));
        assertTrue(eval.getRepairImages().contains("finish_01_watermarked.jpg"));

        // 5. 验证师傅负荷已释放
        WorkerProfile wpAfter = workerProfileMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkerProfile>()
                        .eq(WorkerProfile::getUserId, worker1.getId())
        );
        assertEquals(0, wpAfter.getCurrentLoad(), "完工后师傅在修负荷应原子减 1 释放");

        // 6. 验证流转审计日志
        List<RepairOrderLog> timeline = logService.getTimeline(order.getId());
        RepairOrderLog lastLog = timeline.get(timeline.size() - 1);
        assertEquals("FINISH_UPLOAD", lastLog.getActionType());
        assertEquals("IN_PROGRESS", lastLog.getFromStatus());
        assertEquals("PENDING_ACCEPTANCE", lastLog.getToStatus());
        assertTrue(lastLog.getReasonOrRemark().contains("耗材物料消耗 2 项"));
    }

    @Test
    @DisplayName("物料库存不足时阻断核销并回滚：工单状态保持维修中，库存不被扣减")
    void testFinishOrderInsufficientStockRollback() {
        RepairOrder order = createAssignedAndAcceptedOrder(worker1);

        // 角阀库存当前仅有 10 个，师傅尝试核销 11 个
        WorkOrderFinishRequest finishReq = new WorkOrderFinishRequest();
        finishReq.setRepairSummary("超量领用测试");
        finishReq.setRepairImages(List.of("http://test.com/finish.jpg"));
        finishReq.setMaterialConsumptions(List.of(
                new MaterialConsumeDTO(partValve.getId(), 11)
        ));

        BusinessException ex = assertThrows(BusinessException.class, () ->
                workOrderService.finishOrder(order.getId(), worker1.getId(), finishReq)
        );
        assertEquals(ErrorCode.INVENTORY_OUT_OF_STOCK.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("实际物理库存不足"));

        // 验证事务回滚：工单状态依然是 IN_PROGRESS，库存依然是 10
        RepairOrder rollbackedOrder = orderMapper.selectById(order.getId());
        assertEquals(OrderStatusEnum.IN_PROGRESS.name(), rollbackedOrder.getStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(rollbackedOrder.getMaterialCost()));

        SparePart valve = sparePartMapper.selectById(partValve.getId());
        assertEquals(10, valve.getStockQuantity(), "库存应回滚保持 10");

        WorkerProfile wp = workerProfileMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<WorkerProfile>()
                        .eq(WorkerProfile::getUserId, worker1.getId())
        );
        assertEquals(1, wp.getCurrentLoad(), "师傅负荷应保持 1 不被错误释放");
    }

    @Test
    @DisplayName("越权拦截：非当前工单指派师傅提交完工抛出 AccessDeniedException")
    void testFinishOrderAccessDenied() {
        RepairOrder order = createAssignedAndAcceptedOrder(worker1);

        WorkOrderFinishRequest finishReq = new WorkOrderFinishRequest();
        finishReq.setRepairSummary("他人冒名完工");
        finishReq.setRepairImages(List.of("http://test.com/finish.jpg"));

        assertThrows(AccessDeniedException.class, () ->
                workOrderService.finishOrder(order.getId(), worker2.getId(), finishReq)
        );
    }

    @Test
    @DisplayName("高并发多工单争抢同一紧俏备件库存：行级条件原子扣减杜绝超卖穿透为负")
    void testConcurrentMaterialDeductionAntiOversell() throws Exception {
        // 创建紧俏物料：仅剩 3 个库存
        SparePart tightPart = new SparePart();
        tightPart.setPartCode("MAT-TIGHT-01");
        tightPart.setPartName("紧缺微型传感器");
        tightPart.setCategoryType("其他综合");
        tightPart.setSpecification("V1.0");
        tightPart.setUnit("只");
        tightPart.setGuidePrice(new BigDecimal("100.00"));
        tightPart.setStockQuantity(3);
        tightPart.setSafetyStockThreshold(2);
        tightPart.setStatus(1);
        sparePartMapper.insert(tightPart);

        // 启动 10 个线程，同时针对 tightPart 发起扣减 1 个的请求
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final long dummyOrderId = 1000L + i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    inventoryService.deductMaterialForOrder(
                            dummyOrderId,
                            "BX-CONCURRENT-" + dummyOrderId,
                            worker1.getId(),
                            List.of(new MaterialConsumeDTO(tightPart.getId(), 1))
                    );
                    successCount.incrementAndGet();
                } catch (BusinessException be) {
                    failCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await();
        executor.shutdown();

        // 初始库存 3，每个扣 1：必须恰好 3 个成功，7 个被库存拦截
        assertEquals(3, successCount.get(), "恰好 3 次扣减成功");
        assertEquals(7, failCount.get(), "恰好 7 次因库存不足被阻断");

        // 最终数据库库存必须严格等于 0，绝不可穿透为负数
        SparePart finalPart = sparePartMapper.selectById(tightPart.getId());
        assertEquals(0, finalPart.getStockQuantity(), "库存必须严密归零，不得为负数");
    }

    @Test
    @DisplayName("耗材进销存台账查询与低库存过滤验证")
    void testSparePartsQuery() {
        SparePartQueryRequest req = new SparePartQueryRequest();
        req.setKeyword("防爆角阀");
        var page = inventoryService.getSparePartsPage(req);
        assertTrue(page.getTotal() >= 1);
        assertEquals("PPR防爆角阀20mm", page.getRecords().get(0).getPartName());

        // 查询低库存
        SparePartQueryRequest lowReq = new SparePartQueryRequest();
        lowReq.setLowStockOnly(true);
        var lowPage = inventoryService.getSparePartsPage(lowReq);
        assertNotNull(lowPage);
    }
}
