package com.campus.repair.analytics;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.repair.modules.analytics.dto.WorkOrderFilterQueryRequest;
import com.campus.repair.modules.analytics.service.AnalyticsService;
import com.campus.repair.modules.analytics.vo.DashboardKpiVO;
import com.campus.repair.modules.workorder.vo.DispatchOrderItemVO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分析查询的压测基线（5 万工单量级）。
 *
 * <p><b>为什么用 JUnit 而不是 JMH / k6</b>：要测的是「Spring + MyBatis + H2 端到端」的真实 SQL 与序列化，
 * 而不是单个方法微基准；再引一套运行时（JMH 独立进程、k6 需要 HTTP 服务）会让这条基线在干净环境里跑不起来。
 * JUnit + JdbcTemplate 造数 + 计时，零新增依赖，和现有测试同一命令体系。
 *
 * <p><b>默认不进回归</b>：本类带 {@code @Tag("perf")}，{@code server/pom.xml} 的 surefire 配置
 * 用 {@code <excludedGroups>perf</excludedGroups>} 把它从 {@code mvn test} 排掉——否则每次日常回归
 * 都要多花几秒造 5 万行数据，且慢机器上还会假红。要跑基线：
 * <pre>
 * mvn test -f server/pom.xml -Dgroups=perf -DexcludedGroups= -Dtest=AnalyticsPerformanceTest
 * </pre>
 *
 * <p><b>可调参数</b>：{@code -Dperf.p95.ms=800} 调整阈值（默认 400ms，H2 内存库 + 机器差异大，
 * 这个数字是「明显劣化」的护栏，不是 SLA）；{@code -Dperf.assert=false} 只打印数字不断言，
 * 适合在很慢的机器上留证据。测量结果只打印到标准输出，不落任何报告文件——仓库只承载产物。
 *
 * <p><b>为什么造数要「清空再灌」</b>：测试库是 {@code jdbc:h2:mem:testdb}，同一 JVM 里
 * 多个测试类共享同一份内存库；不先清空，5 万行的分母就取决于执行顺序，p50/p95 与
 * {@code total >= 50000} 的断言会随运行次序漂移。
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("perf")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnalyticsPerformanceTest {

    /** 工单总量：覆盖 10 分类 × 30 空间 × 12 个月，每格约 14 单，足以让聚合与深分页都有量 */
    private static final int ORDER_COUNT = 50_000;
    /** 评价量：只覆盖前 2 万张已结单工单（评价与结单 1:1，但不必全量——KPI 只求和/计数） */
    private static final int EVALUATION_COUNT = 20_000;
    private static final int CATEGORY_COUNT = 10;
    private static final int SPACE_COUNT = 30;
    private static final int MONTH_COUNT = 12;
    /** 批大小上限：一次 5 万行会让驱动攒出巨大的参数数组，按 1000 行一批既能摊平内存也够快 */
    private static final int BATCH_SIZE = 1_000;
    /** 预热次数：H2 需要先把相关页读进缓存、语句计划缓存也需要几轮才稳定 */
    private static final int WARMUP_ROUNDS = 3;
    /** 采样次数：20 次足够算 p50/p95，又不至于让单次基线跑太久 */
    private static final int SAMPLE_ROUNDS = 20;

    private static final String ORDER_INSERT_SQL = "INSERT INTO repair_order ("
            + "order_no, student_user_id, applicant_name, applicant_phone, space_id, space_full_path, "
            + "category_id, category_full_name, worker_skill_code, description, urgency_level, appointment_period, "
            + "status, review_status, rework_count, is_archived, created_at, closed_time, deleted"
            + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private static final String EVALUATION_INSERT_SQL = "INSERT INTO repair_order_evaluation ("
            + "order_id, order_no, repair_summary, repair_images, finish_reported_at, "
            + "rating_star, evaluated_at, is_auto_confirmed, rework_flag"
            + ") VALUES (?,?,?,?,?,?,?,?,?)";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AnalyticsService analyticsService;

    /** 只打印的数字（无论断言开关如何都输出，供 scripts/perf.ps1 抓取） */
    private double kpiP50Ms;
    private double kpiP95Ms;
    private long kpiRows;

    private double pageP50Ms;
    private double pageP95Ms;
    private long pageTotal;

    @BeforeAll
    void seedDataset() {
        // 先清空：repair_order_evaluation 必须比 repair_order 先删（评价行回指工单）
        jdbcTemplate.update("DELETE FROM repair_order_evaluation");
        jdbcTemplate.update("DELETE FROM repair_order");

        long startNanos = System.nanoTime();
        seedOrders();
        seedEvaluations();
        long costMs = (System.nanoTime() - startNanos) / 1_000_000;
        // 刻意不用 [perf] 前缀：标准输出里只有两行 [perf]（kpi / page），抓取脚本才不会多抓
        System.out.printf("[dataset] orders=%d evaluations=%d cost=%dms%n",
                ORDER_COUNT, EVALUATION_COUNT, costMs);
    }

    private void seedOrders() {
        LocalDateTime base = LocalDateTime.now();
        // 状态混合：约 70% 已结单（返工率/满意度/平均时效的分母），其余是在办与待派单，
        // 让 KPI 的 CASE WHEN 同时命中多个分支，而不是只测到一条路径
        String[] statuses = {"COMPLETED", "COMPLETED", "COMPLETED", "COMPLETED", "COMPLETED", "COMPLETED",
                "COMPLETED", "PENDING_DISPATCH", "ASSIGNED", "IN_PROGRESS"};

        for (int start = 0; start < ORDER_COUNT; start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, ORDER_COUNT);
            List<Object[]> batch = new ArrayList<>(end - start);
            for (int i = start; i < end; i++) {
                String status = statuses[i % statuses.length];
                int category = i % CATEGORY_COUNT;
                int space = (i / CATEGORY_COUNT) % SPACE_COUNT;
                int month = i % MONTH_COUNT;
                // 12 个月均匀回溯；再叠加分钟偏移，避免同一天同一秒的极端重复
                LocalDateTime createdAt = base.minusMonths(month).minusMinutes(i % 1440L);
                boolean completed = "COMPLETED".equals(status);
                LocalDateTime closedAt = completed ? createdAt.plusHours(3) : null;
                // 返工率约 8%：每 12 单有 1 单返工（只落在已结单上，KPI 的分子只数已结单）
                int reworkCount = completed && i % 12 == 0 ? 1 : 0;

                batch.add(new Object[]{
                        String.format("BXPERF%08d", i),
                        1L + (i % 100),                       // 报修人分散在 100 个虚拟用户上
                        "压测用户" + (i % 100),
                        "139" + String.format("%08d", i % 100_000_000),
                        (long) (space + 1),
                        "校区" + (space / 10 + 1) + "/楼栋" + (space % 10 + 1),
                        (long) (category + 1),
                        "故障大类" + category,
                        "SKILL_" + category,                  // 10 个工种，与分类一一对应
                        "压测工单描述-" + i,
                        1,
                        "上午08:30-11:30",
                        status,
                        "PASSED",
                        reworkCount,
                        0,
                        createdAt,
                        closedAt,
                        0
                });
            }
            jdbcTemplate.batchUpdate(ORDER_INSERT_SQL, batch);
        }
    }

    private void seedEvaluations() {
        // 评价必须是「已结单工单」的评价：先取已结单的 id/单号，再 1:1 落评价
        List<Map<String, Object>> completed = jdbcTemplate.queryForList(
                "SELECT id, order_no FROM repair_order WHERE status = 'COMPLETED' ORDER BY id LIMIT " + EVALUATION_COUNT);
        LocalDateTime base = LocalDateTime.now();

        for (int start = 0; start < completed.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, completed.size());
            List<Object[]> batch = new ArrayList<>(end - start);
            for (int i = start; i < end; i++) {
                Map<String, Object> row = completed.get(i);
                // 1~5 星均匀分布：既覆盖满意度均分，也覆盖「自动核销未评分」之外的正常路径
                int star = 1 + i % 5;
                batch.add(new Object[]{
                        ((Number) row.get("id")).longValue(),
                        row.get("order_no"),
                        "压测完工说明-" + i,
                        "[]",
                        base.minusDays(i % 30),
                        star,
                        base.minusDays(i % 30),
                        0,
                        i % 12 == 0 ? 1 : 0
                });
            }
            jdbcTemplate.batchUpdate(EVALUATION_INSERT_SQL, batch);
        }
    }

    private static double percentile(List<Long> sortedNanos, double percentile) {
        int size = sortedNanos.size();
        int index = (int) Math.ceil(percentile * size) - 1;
        int safeIndex = Math.min(Math.max(index, 0), size - 1);
        return sortedNanos.get(safeIndex) / 1_000_000.0;
    }

    @Test
    @DisplayName("大屏 KPI 聚合：5 万单量级下 p50/p95 基线")
    void testDashboardKpiLatencyUnderFiftyThousandOrders() {
        assertEquals(ORDER_COUNT, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM repair_order", Integer.class).intValue(),
                "造数应恰好落 5 万张工单，否则后面的分位统计不可比");

        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            analyticsService.getDashboardKpi();
        }

        List<Long> samples = new ArrayList<>(SAMPLE_ROUNDS);
        DashboardKpiVO last = null;
        for (int i = 0; i < SAMPLE_ROUNDS; i++) {
            long start = System.nanoTime();
            last = analyticsService.getDashboardKpi();
            samples.add(System.nanoTime() - start);
        }
        kpiRows = last != null && last.getTotalOrderCount() != null ? last.getTotalOrderCount() : -1;

        samples.sort(Long::compareTo);
        kpiP50Ms = percentile(samples, 0.50);
        kpiP95Ms = percentile(samples, 0.95);

        // 输出格式是 scripts/perf.ps1 的抓取契约：一行一条，前缀固定
        System.out.printf("[perf] kpi p50=%.1fms p95=%.1fms rows=%d%n", kpiP50Ms, kpiP95Ms, kpiRows);

        assertTrue(kpiRows >= ORDER_COUNT, "KPI 累计工单量应覆盖造的全量数据，实际=" + kpiRows);
        assertWithinThreshold("kpi", kpiP95Ms);
    }

    @Test
    @DisplayName("多维检索深分页：size=10 / page=100 的 p50/p95 基线")
    void testOrderFilterPageLatencyUnderFiftyThousandOrders() {
        // 过滤条件刻意「不缩表」：状态取全部四种生成值 + 未归档 + 时间下界，
        // 命中全量 5 万行，测的是「count(*) 全表 + 深分页取 10 行」这一最重组合；
        // 若再叠加分类等值过滤，total 会掉到 5000，与 total>=50000 的验收口径冲突，
        // 而分类等值过滤本身的正确性已由既有分析舱集成用例覆盖。
        WorkOrderFilterQueryRequest request = new WorkOrderFilterQueryRequest();
        request.setPage(100);
        request.setSize(10);
        request.setIsArchived(0);
        request.setStatuses(Arrays.asList("COMPLETED", "PENDING_DISPATCH", "ASSIGNED", "IN_PROGRESS"));
        request.setCreateTimeStart("2020-01-01 00:00:00");

        for (int i = 0; i < WARMUP_ROUNDS; i++) {
            analyticsService.queryOrdersWithFilter(request);
        }

        List<Long> samples = new ArrayList<>(SAMPLE_ROUNDS);
        Page<DispatchOrderItemVO> last = null;
        for (int i = 0; i < SAMPLE_ROUNDS; i++) {
            long start = System.nanoTime();
            last = analyticsService.queryOrdersWithFilter(request);
            samples.add(System.nanoTime() - start);
        }
        pageTotal = last != null ? last.getTotal() : -1;

        samples.sort(Long::compareTo);
        pageP50Ms = percentile(samples, 0.50);
        pageP95Ms = percentile(samples, 0.95);

        System.out.printf("[perf] page p50=%.1fms p95=%.1fms total=%d%n", pageP50Ms, pageP95Ms, pageTotal);

        assertTrue(pageTotal >= ORDER_COUNT, "过滤后总数应覆盖全量 5 万单，实际=" + pageTotal);
        assertWithinThreshold("page", pageP95Ms);
    }

    /**
     * 阈值断言。默认 400ms 是「明显劣化」的护栏（H2 内存库、机器差异大），
     * 需要更严或更松时用 {@code -Dperf.p95.ms=...} 覆盖；{@code -Dperf.assert=false} 只打印不断言。
     */
    private void assertWithinThreshold(String label, double p95Ms) {
        if (!Boolean.parseBoolean(System.getProperty("perf.assert", "true"))) {
            System.out.printf("[perf-skip] %s threshold skipped (-Dperf.assert=false), p95=%.1fms%n", label, p95Ms);
            return;
        }
        double threshold = Double.parseDouble(System.getProperty("perf.p95.ms", "400"));
        assertTrue(p95Ms <= threshold,
                String.format("%s p95=%.1fms 超过阈值 %.0fms（可用 -Dperf.p95.ms=... 调整，"
                        + "或用 -Dperf.assert=false 只取数字）", label, p95Ms, threshold));
    }
}
