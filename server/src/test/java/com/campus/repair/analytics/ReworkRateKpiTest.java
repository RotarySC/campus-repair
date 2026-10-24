package com.campus.repair.analytics;

import com.campus.repair.CampusRepairApplication;
import com.campus.repair.modules.analytics.service.AnalyticsService;
import com.campus.repair.modules.analytics.vo.DashboardKpiVO;
import com.campus.repair.modules.workorder.entity.RepairOrder;
import com.campus.repair.modules.workorder.entity.RepairOrderEvaluation;
import com.campus.repair.modules.workorder.mapper.RepairOrderEvaluationMapper;
import com.campus.repair.modules.workorder.mapper.RepairOrderMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Phase 05 · 返工率 KPI（K5 / R49）的口径回归测试。
 *
 * <p><b>被锁定的口径</b>：{@code reworkRatePercent} = 已结单且 {@code rework_count > 0} 的
 * <b>工单数</b> / 已结单工单数 × 100，{@code HALF_UP} 保留 2 位小数，<b>按单去重</b>。
 * 同时返回 {@code completedOrderCount} 作为分母，让前端能区分「还没有结单」（渲染「—」）
 * 与「有结单且零返工」（渲染 0.00%）。
 *
 * <p><b>为什么每个用例都先 {@link #isolateOrderScope()} 清场</b>：测试库是同一 JVM 内共享的
 * 内存 H2（{@code jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1}），非事务型集成测试可能留下已提交工单。
 * 直接断言全局计数会随用例执行顺序漂移。清场发生在<b>本用例自己的事务</b>里，方法结束即回滚，
 * 不影响其他用例——这样「5 单结单、2 单返工 → 40.00%」这类闭式断言才是稳定的。
 */
@SpringBootTest(classes = CampusRepairApplication.class)
@ActiveProfiles("test")
@Transactional
public class ReworkRateKpiTest {

    @Autowired
    private AnalyticsService analyticsService;

    @Autowired
    private RepairOrderMapper orderMapper;

    @Autowired
    private RepairOrderEvaluationMapper evaluationMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private int seq = 0;

    /**
     * 把库里已有的工单整体逻辑删除，使本用例只看到自己插入的数据（事务结束回滚）。
     */
    private void isolateOrderScope() {
        jdbcTemplate.update("UPDATE repair_order SET deleted = 1 WHERE deleted = 0");
    }

    private Long insertOrder(String status, int reworkCount,
                             LocalDateTime createdAt, LocalDateTime closedTime) {
        seq++;
        RepairOrder o = new RepairOrder();
        o.setOrderNo("BXRWK" + System.nanoTime() + seq);
        o.setStudentUserId(1L);
        o.setApplicantName("返工率口径测试用户");
        o.setApplicantPhone("13800000000");
        o.setSpaceId(1L);
        o.setSpaceFullPath("东校区/学生生活区/1号楼");
        o.setCategoryId(1L);
        o.setCategoryFullName("水暖管道/爆管");
        o.setDescription("返工率 KPI 口径回归单");
        o.setAppointmentPeriod("工作日");
        o.setUrgencyLevel(1);
        o.setStatus(status);
        o.setReworkCount(reworkCount);
        o.setIsArchived(0);
        o.setDeleted(0);
        o.setCreatedAt(createdAt);
        o.setClosedTime(closedTime);
        o.setUpdatedAt(createdAt);
        orderMapper.insert(o);
        return o.getId();
    }

    /** 自动验收（未评分）的结单：口径上照常计入返工率分母，只是不进满意度。 */
    private void insertAutoConfirmedEvaluation(Long orderId, String orderNo) {
        RepairOrderEvaluation e = new RepairOrderEvaluation();
        e.setOrderId(orderId);
        e.setOrderNo(orderNo);
        e.setRepairSummary("自动验收无评分完工说明");
        e.setRepairImages("[]");
        e.setFinishReportedAt(LocalDateTime.now());
        e.setRatingStar(null);
        e.setIsAutoConfirmed(1);
        e.setEvaluatedAt(LocalDateTime.now());
        evaluationMapper.insert(e);
    }

    private Long insertCompleted(int reworkCount, LocalDateTime base, int offsetHours) {
        return insertOrder("COMPLETED", reworkCount, base, base.plusHours(offsetHours));
    }

    @Test
    @DisplayName("无任何结单：completedOrderCount=0 且 reworkRatePercent=0.00（前端据此渲染「—」）")
    public void testNoCompletedOrderHasZeroDenominator() {
        isolateOrderScope();
        LocalDateTime now = LocalDateTime.now();
        // 只有未结单工单：其中一张还是「在办返工」——终态口径下两者都不该出现
        insertOrder("PENDING_DISPATCH", 0, now.minusMinutes(10), null);
        insertOrder("REWORK_PENDING", 1, now.minusHours(2), null);
        insertOrder("IN_PROGRESS", 2, now.minusHours(3), null);

        DashboardKpiVO kpi = analyticsService.getDashboardKpi();

        Assertions.assertEquals(0L, kpi.getCompletedOrderCount().longValue(),
                "没有结单工单时分母必须为 0（前端据此区分「无数据」与 0%）");
        Assertions.assertEquals(0, BigDecimal.ZERO.compareTo(kpi.getReworkRatePercent()),
                "无结单时返工率必须是 0.00，不能是 100 或 null，实际 " + kpi.getReworkRatePercent());
    }

    @Test
    @DisplayName("5 单结单、2 单触发返工 → 40.00%；同一单返工 3 次仍只算 1 单（按单去重）")
    public void testFiveCompletedTwoReworkedIsFortyPercent() {
        isolateOrderScope();
        LocalDateTime base = LocalDateTime.now().minusDays(2);
        insertCompleted(0, base, 1);
        insertCompleted(0, base, 2);
        insertCompleted(0, base, 3);
        insertCompleted(1, base, 4);
        // 同一单返工 3 次：若按次数求和会变成 (1+3)/5 = 80%，按单去重才是 2/5 = 40%
        insertCompleted(3, base, 5);
        // 未结单的返工单：既不是 COMPLETED，也不该进分子分母
        insertOrder("REWORK_PENDING", 1, base, null);
        insertOrder("ASSIGNED", 2, base, null);

        DashboardKpiVO kpi = analyticsService.getDashboardKpi();

        Assertions.assertEquals(5L, kpi.getCompletedOrderCount().longValue(), "返工率分母 = 已结单工单数");
        Assertions.assertEquals("40.00", kpi.getReworkRatePercent().toPlainString(),
                "2 单返工 / 5 单结单 应为 40.00%，实际 " + kpi.getReworkRatePercent());

        // 独立 SQL 核对：分子是「工单数」，不是「返工次数和」
        Integer rawReworkOrders = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM repair_order WHERE deleted = 0 AND status = 'COMPLETED' AND rework_count > 0",
                Integer.class);
        Integer rawReworkSum = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(rework_count), 0) FROM repair_order WHERE deleted = 0 AND status = 'COMPLETED'",
                Integer.class);
        Assertions.assertEquals(2, rawReworkOrders, "触发过返工的已结单工单数应为 2");
        Assertions.assertEquals(4, rawReworkSum, "返工次数之和应为 4（1 + 3），与按单去重的 2 必须区分");
        Assertions.assertNotEquals(rawReworkSum.intValue(), rawReworkOrders.intValue(),
                "本用例的数据刻意让「次数」与「单数」不相等，否则去重口径没有被真正覆盖");
    }

    @Test
    @DisplayName("有结单但零返工：分母非 0、返工率为 0.00（应渲染 0.00% 而不是「—」）")
    public void testCompletedWithoutReworkIsZeroWithNonZeroDenominator() {
        isolateOrderScope();
        LocalDateTime base = LocalDateTime.now().minusDays(3);
        insertCompleted(0, base, 1);
        insertCompleted(0, base, 2);

        DashboardKpiVO kpi = analyticsService.getDashboardKpi();

        Assertions.assertEquals(2L, kpi.getCompletedOrderCount().longValue(),
                "有结单时分母必须反映真实结单数");
        Assertions.assertEquals(0, BigDecimal.ZERO.compareTo(kpi.getReworkRatePercent()),
                "零返工应为 0.00，实际 " + kpi.getReworkRatePercent());
    }

    @Test
    @DisplayName("舍入口径：6 单结单、1 单返工 → 16.67%（HALF_UP 保留 2 位）")
    public void testRoundingUsesHalfUpTwoDecimals() {
        isolateOrderScope();
        LocalDateTime base = LocalDateTime.now().minusDays(4);
        insertCompleted(1, base, 1);
        for (int i = 2; i <= 6; i++) {
            insertCompleted(0, base, i);
        }

        DashboardKpiVO kpi = analyticsService.getDashboardKpi();

        Assertions.assertEquals(6L, kpi.getCompletedOrderCount().longValue());
        // 1/6 = 16.666...% → HALF_UP 2 位 = 16.67（不是截断的 16.66）
        Assertions.assertEquals("16.67", kpi.getReworkRatePercent().toPlainString(),
                "1/6 应四舍五入为 16.67%，实际 " + kpi.getReworkRatePercent());
    }

    @Test
    @DisplayName("自动验收（is_auto_confirmed=1）的结单照常计入返工率分母，不因未评分被剔除")
    public void testAutoConfirmedCompletedCountsInDenominator() {
        isolateOrderScope();
        LocalDateTime base = LocalDateTime.now().minusDays(5);
        Long autoConfirmed = insertCompleted(1, base, 1);
        insertAutoConfirmedEvaluation(autoConfirmed, "BXRWK-EVAL-" + autoConfirmed);
        insertCompleted(0, base, 2);
        insertCompleted(0, base, 3);

        DashboardKpiVO kpi = analyticsService.getDashboardKpi();

        Assertions.assertEquals(3L, kpi.getCompletedOrderCount().longValue(),
                "自动核销未评分的结单同样是已结单，必须进分母");
        Assertions.assertEquals("33.33", kpi.getReworkRatePercent().toPlainString(),
                "1 单返工 / 3 单结单 应为 33.33%，实际 " + kpi.getReworkRatePercent());
    }

    @Test
    @DisplayName("口径恒在 [0, 100]：返工率不会因多次返工突破 100%")
    public void testReworkRateNeverExceedsHundredPercent() {
        isolateOrderScope();
        LocalDateTime base = LocalDateTime.now().minusDays(6);
        // 唯一一张结单被返工 9 次：按次数口径会是 900%，按单去重才是 100%
        insertCompleted(9, base, 1);

        DashboardKpiVO kpi = analyticsService.getDashboardKpi();

        Assertions.assertEquals(1L, kpi.getCompletedOrderCount().longValue());
        Assertions.assertEquals("100.00", kpi.getReworkRatePercent().toPlainString(),
                "同一单多次返工只能算 1 单，率不得超过 100%，实际 " + kpi.getReworkRatePercent());
        Assertions.assertTrue(kpi.getReworkRatePercent().compareTo(BigDecimal.valueOf(100)) <= 0,
                "返工率是比率，恒 ≤ 100%");
    }
}
