package com.campus.repair.analytics;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.repair.CampusRepairApplication;
import com.campus.repair.modules.analytics.dto.WorkOrderFilterQueryRequest;
import com.campus.repair.modules.analytics.service.AnalyticsService;
import com.campus.repair.modules.analytics.vo.*;
import com.campus.repair.modules.spatial.entity.RepairCategory;
import com.campus.repair.modules.spatial.entity.SpatialNode;
import com.campus.repair.modules.spatial.service.RepairCategoryService;
import com.campus.repair.modules.spatial.service.SpatialNodeService;
import com.campus.repair.modules.user.entity.SysUser;
import com.campus.repair.modules.user.mapper.SysUserMapper;
import com.campus.repair.modules.workorder.dto.WorkOrderFinishRequest;
import com.campus.repair.modules.workorder.dto.WorkOrderManualDispatchRequest;
import com.campus.repair.modules.workorder.dto.WorkOrderSubmitRequest;
import com.campus.repair.modules.workorder.entity.RepairOrder;
import com.campus.repair.modules.workorder.enums.OrderStatusEnum;
import com.campus.repair.modules.workorder.mapper.RepairOrderMapper;
import com.campus.repair.modules.workorder.service.WorkOrderService;
import com.campus.repair.modules.workorder.vo.DispatchOrderItemVO;
import com.campus.repair.modules.workorder.vo.WorkOrderSubmitResultVO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@SpringBootTest(classes = CampusRepairApplication.class)
@ActiveProfiles("test")
public class AnalyticsCockpitIntegrationTest {

    @Autowired
    private AnalyticsService analyticsService;

    @Autowired
    private WorkOrderService workOrderService;

    @Autowired
    private RepairOrderMapper orderMapper;

    @Autowired
    private SpatialNodeService spatialNodeService;

    @Autowired
    private RepairCategoryService categoryService;

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private com.campus.repair.modules.user.mapper.WorkerProfileMapper workerProfileMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long testRoomId;
    private Long testCategoryId;
    private Long testStudentId;
    private Long testWorkerId;

    @BeforeEach
    public void setUp() {
        SpatialNode campus = new SpatialNode();
        campus.setName("指挥大屏测试校区");
        campus.setLevelType(1);
        campus = spatialNodeService.createNode(campus);

        SpatialNode room = new SpatialNode();
        room.setParentId(campus.getId());
        room.setName("物联大屏201");
        room.setLevelType(4);
        room = spatialNodeService.createNode(room);
        testRoomId = room.getId();

        RepairCategory cat = new RepairCategory();
        cat.setName("水暖管道/爆管漏水");
        cat.setLevel(2);
        cat.setWorkerSkillCode("PLUMBER");
        cat.setSlaHours(12);
        cat = categoryService.createCategory(cat);
        testCategoryId = cat.getId();

        SysUser student = new SysUser();
        student.setUsername("student_cockpit_" + System.currentTimeMillis());
        student.setRealName("大屏测试师生");
        student.setPhone("13966660001");
        student.setPassword(passwordEncoder.encode("123456"));
        student.setUserType("STUDENT");
        student.setStatus(1);
        student.setIsFirstLogin(0);
        userMapper.insert(student);
        testStudentId = student.getId();

        SysUser worker = new SysUser();
        worker.setUsername("worker_cockpit_" + System.currentTimeMillis());
        worker.setRealName("大屏金牌师傅");
        worker.setPhone("13966660002");
        worker.setPassword(passwordEncoder.encode("123456"));
        worker.setUserType("LOGISTICS");
        worker.setStatus(1);
        worker.setIsFirstLogin(0);
        userMapper.insert(worker);
        testWorkerId = worker.getId();

        com.campus.repair.modules.user.entity.WorkerProfile profile = new com.campus.repair.modules.user.entity.WorkerProfile();
        profile.setUserId(testWorkerId);
        profile.setSkillCode("PLUMBER");
        profile.setCampusId(campus.getId());
        profile.setMaxLoad(5);
        profile.setCurrentLoad(0);
        profile.setOnDutyStatus(1);
        profile.setRatingAvg(new java.math.BigDecimal("4.95"));
        profile.setTotalCompletedCount(10);
        profile.setCreatedAt(LocalDateTime.now());
        profile.setUpdatedAt(LocalDateTime.now());
        workerProfileMapper.insert(profile);
    }

    @Test
    @DisplayName("测试大屏 7 项核心 KPI 聚合统计（含返工率）与趋势图表数据结构准确性")
    public void testCockpitKpiAndTrendAggregation() {
        // 1. 获取基础指标
        DashboardKpiVO kpi = analyticsService.getDashboardKpi();
        Assertions.assertNotNull(kpi);
        Assertions.assertTrue(kpi.getTotalOrderCount() >= 0);
        Assertions.assertNotNull(kpi.getCompletionRatePercent());
        Assertions.assertNotNull(kpi.getAverageResponseHours());
        Assertions.assertNotNull(kpi.getSatisfactionScore());

        // 1.1 Phase 05 新增：返工率（K5 / R49）与它的分母。本用例不断言具体数值
        //     （测试库为共享内存库，全局计数随其他用例执行顺序漂移），只锁契约与不变式：
        //     ① 两字段必须非空；② 率恒在 [0, 100]；③ 无结单时率必须为 0.00（前端据此渲染「—」）
        Assertions.assertNotNull(kpi.getCompletedOrderCount(), "返工率分母 completedOrderCount 不得为 null");
        Assertions.assertNotNull(kpi.getReworkRatePercent(), "返工率 reworkRatePercent 不得为 null");
        Assertions.assertTrue(kpi.getCompletedOrderCount() >= 0, "返工率分母不得为负");
        Assertions.assertTrue(kpi.getReworkRatePercent().compareTo(java.math.BigDecimal.ZERO) >= 0,
                "返工率不得为负，实际 " + kpi.getReworkRatePercent());
        Assertions.assertTrue(kpi.getReworkRatePercent().compareTo(java.math.BigDecimal.valueOf(100)) <= 0,
                "返工率是比率，恒 ≤ 100%，实际 " + kpi.getReworkRatePercent());
        if (kpi.getCompletedOrderCount() == 0L) {
            Assertions.assertEquals(0, java.math.BigDecimal.ZERO.compareTo(kpi.getReworkRatePercent()),
                    "无结单工单时返工率必须为 0.00，由前端渲染「—」");
        }

        // 2. 获取 30 天双折线趋势
        TrendChartVO trend = analyticsService.getTrendChart(30);
        Assertions.assertEquals(30, trend.getDates().size());
        Assertions.assertEquals(30, trend.getSubmitCounts().size());
        Assertions.assertEquals(30, trend.getCompletedCounts().size());

        // 3. 获取空间热力与分类分布
        List<SpatialHeatVO> heatList = analyticsService.getSpatialDistribution(5);
        Assertions.assertNotNull(heatList);

        List<CategoryRatioVO> catList = analyticsService.getCategoryDistribution();
        Assertions.assertNotNull(catList);
    }

    @Test
    @DisplayName("测试工单多维高级检索、多条件组合过滤与 Excel 导出模型解析")
    public void testOrderMultiFilterAndExcelExportModel() {
        // 提单并指派
        WorkOrderSubmitRequest sub = new WorkOrderSubmitRequest();
        sub.setSpaceId(testRoomId);
        sub.setCategoryId(testCategoryId);
        sub.setDescription("走廊消防管高压爆裂急需抢修");
        sub.setUrgencyLevel(2); // 加急
        sub.setAppointmentPeriod("即刻上门");
        WorkOrderSubmitResultVO subRes = workOrderService.submitOrder(testStudentId, sub);
        Long orderId = subRes.getOrderId();

        // 调度指派
        WorkOrderManualDispatchRequest dispatchReq = new WorkOrderManualDispatchRequest();
        dispatchReq.setWorkerId(testWorkerId);
        dispatchReq.setDispatchRemark("调度中心紧急指派");
        workOrderService.manualDispatch(orderId, 1L, dispatchReq);

        // 1. 测试按加急状态过滤
        WorkOrderFilterQueryRequest filterReq = new WorkOrderFilterQueryRequest();
        filterReq.setIsUrgent(1);
        filterReq.setKeyword("消防管");
        Page<DispatchOrderItemVO> pageRes = analyticsService.queryOrdersWithFilter(filterReq);
        Assertions.assertTrue(pageRes.getTotal() >= 1);
        Assertions.assertTrue(pageRes.getRecords().stream().anyMatch(o -> o.getId().equals(orderId)));

        // 2. 测试 Excel 导出模型组装
        List<WorkOrderExportVO> exportList = analyticsService.getOrdersForExport(filterReq);
        Assertions.assertFalse(exportList.isEmpty());
        WorkOrderExportVO exportItem = exportList.stream()
                .filter(e -> subRes.getOrderNo().equals(e.getOrderNo()))
                .findFirst()
                .orElse(null);

        Assertions.assertNotNull(exportItem);
        Assertions.assertEquals("【加急】", exportItem.getUrgencyDesc());
        Assertions.assertEquals("活跃业务数据", exportItem.getArchiveDesc());
        Assertions.assertTrue(exportItem.getDescription().contains("消防管高压爆裂"));
    }

    @Test
    @DisplayName("测试超过 180 天历史老旧工单冷数据归档隔离")
    @Transactional
    public void testHistoricalOrderColdDataArchiving() {
        // 创建一张已结单且闭环时间在 200 天前的老旧工单
        RepairOrder oldOrder = new RepairOrder();
        oldOrder.setOrderNo("BXOLD" + System.currentTimeMillis());
        oldOrder.setStudentUserId(testStudentId);
        oldOrder.setApplicantName("历史用户");
        oldOrder.setApplicantPhone("13800000000");
        oldOrder.setSpaceId(testRoomId);
        oldOrder.setSpaceFullPath("历史校区/老楼101");
        oldOrder.setCategoryId(testCategoryId);
        oldOrder.setCategoryFullName("历史类别");
        oldOrder.setDescription("两年前的门锁故障");
        oldOrder.setAppointmentPeriod("周末全天");
        oldOrder.setUrgencyLevel(1);
        oldOrder.setStatus(OrderStatusEnum.COMPLETED.name());
        oldOrder.setClosedTime(LocalDateTime.now().minusDays(200));
        oldOrder.setIsArchived(0);
        oldOrder.setCreatedAt(LocalDateTime.now().minusDays(201));
        oldOrder.setUpdatedAt(LocalDateTime.now().minusDays(200));
        oldOrder.setDeleted(0);
        orderMapper.insert(oldOrder);

        // 执行归档 (门槛 180 天)
        int archivedCount = analyticsService.archiveHistoricalOrders(180);
        Assertions.assertTrue(archivedCount >= 1, "应至少归档 1 张历史老旧工单");

        // 校验该工单归档标记更新为 1
        RepairOrder check = orderMapper.selectById(oldOrder.getId());
        Assertions.assertEquals(1, check.getIsArchived(), "历史工单归档状态必须变更为 1");

        // 默认多维检索 (isArchived = 0) 应已自动隔离该工单
        WorkOrderFilterQueryRequest activeQuery = new WorkOrderFilterQueryRequest();
        activeQuery.setKeyword(oldOrder.getOrderNo());
        activeQuery.setIsArchived(0);
        Page<DispatchOrderItemVO> activePage = analyticsService.queryOrdersWithFilter(activeQuery);
        Assertions.assertEquals(0, activePage.getTotal(), "默认活跃查询应排除已归档冷数据");

        // 显式查询归档冷数据 (isArchived = 1) 可检索到
        WorkOrderFilterQueryRequest archiveQuery = new WorkOrderFilterQueryRequest();
        archiveQuery.setKeyword(oldOrder.getOrderNo());
        archiveQuery.setIsArchived(1);
        Page<DispatchOrderItemVO> archivePage = analyticsService.queryOrdersWithFilter(archiveQuery);
        Assertions.assertEquals(1, archivePage.getTotal(), "显式指定归档库可检索到冷数据");
    }
}
