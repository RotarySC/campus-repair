package com.campus.repair.analytics;

import com.campus.repair.CampusRepairApplication;
import com.campus.repair.modules.analytics.service.AnalyticsService;
import com.campus.repair.modules.analytics.vo.CategoryRatioVO;
import com.campus.repair.modules.analytics.vo.DashboardKpiVO;
import com.campus.repair.modules.analytics.vo.SpatialHeatVO;
import com.campus.repair.modules.analytics.vo.TrendChartVO;
import com.campus.repair.modules.analytics.vo.WorkerLeaderboardVO;
import com.campus.repair.modules.user.entity.SysUser;
import com.campus.repair.modules.user.entity.WorkerProfile;
import com.campus.repair.modules.user.mapper.SysUserMapper;
import com.campus.repair.modules.user.mapper.WorkerProfileMapper;
import com.campus.repair.modules.workorder.entity.RepairOrder;
import com.campus.repair.modules.workorder.entity.RepairOrderEvaluation;
import com.campus.repair.modules.workorder.mapper.RepairOrderEvaluationMapper;
import com.campus.repair.modules.workorder.mapper.RepairOrderMapper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 分析模块「聚合下推 SQL」的行为等价性回归测试。
 *
 * <p>本测试刻意把重构前 {@code AnalyticsServiceImpl} 的内存聚合算法原样保留为
 * <b>参考实现</b>（{@code ref*} 系列方法），用同一批数据分别跑参考实现与新的
 * SQL 聚合实现，断言两者逐项一致——包括空间标签截断、故障大类根节点、
 * 聚类排序与技师榜单排序。这样既锁住「改动不改变数值口径」，也覆盖了
 * SQL 与 Java 最可能悄悄分歧的边界（单段路径、两段路径、多段路径、无斜杠类别）。
 */
@SpringBootTest(classes = CampusRepairApplication.class)
@ActiveProfiles("test")
@Transactional
public class AnalyticsAggregationEquivalenceTest {

    @Autowired
    private AnalyticsService analyticsService;

    @Autowired
    private RepairOrderMapper orderMapper;

    @Autowired
    private RepairOrderEvaluationMapper evaluationMapper;

    @Autowired
    private WorkerProfileMapper workerProfileMapper;

    @Autowired
    private SysUserMapper userMapper;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String SPACE_FALLBACK = "其他空间";
    private static final String CATEGORY_FALLBACK = "综合报修";

    private static Map<String, Long> toSpaceMap(List<SpatialHeatVO> list) {
        Map<String, Long> map = new java.util.TreeMap<>();
        list.forEach(h -> map.put(h.getSpaceName(), h.getOrderCount()));
        return map;
    }

    private int seq = 0;

    // ------------------------------------------------------------------
    // 数据构造
    // ------------------------------------------------------------------

    private Long insertOrder(String spacePath, String categoryPath, String status,
                             LocalDateTime createdAt, LocalDateTime closedTime, Long workerId) {
        seq++;
        RepairOrder o = new RepairOrder();
        o.setOrderNo("BXEQ" + System.nanoTime() + seq);
        o.setStudentUserId(1L);
        o.setApplicantName("等价性测试用户");
        o.setApplicantPhone("13800000000");
        o.setSpaceId(1L);
        o.setSpaceFullPath(spacePath);
        o.setCategoryId(1L);
        o.setCategoryFullName(categoryPath);
        o.setDescription("聚合口径等价性验证单");
        o.setAppointmentPeriod("工作日");
        o.setUrgencyLevel(1);
        o.setStatus(status);
        o.setWorkerId(workerId);
        o.setIsArchived(0);
        o.setDeleted(0);
        o.setCreatedAt(createdAt);
        o.setClosedTime(closedTime);
        o.setUpdatedAt(createdAt);
        orderMapper.insert(o);
        return o.getId();
    }

    private void insertEvaluation(Long orderId, Integer ratingStar) {
        RepairOrderEvaluation e = new RepairOrderEvaluation();
        e.setOrderId(orderId);
        e.setOrderNo("BXEQ-EVAL-" + orderId);
        e.setRepairSummary("聚合口径等价性验证完工说明");
        e.setRepairImages("[]");
        e.setFinishReportedAt(LocalDateTime.now());
        e.setRatingStar(ratingStar);
        e.setIsAutoConfirmed(ratingStar == null ? 1 : 0);
        e.setEvaluatedAt(LocalDateTime.now());
        evaluationMapper.insert(e);
    }

    private Long insertWorker(String name, String skillCode, BigDecimal ratingAvg) {
        SysUser u = new SysUser();
        u.setUsername("eqworker_" + System.nanoTime() + seq);
        u.setRealName(name);
        u.setPhone("13900000000");
        u.setPassword("x");
        u.setUserType("REPAIR_WORKER");
        u.setStatus(1);
        u.setIsFirstLogin(0);
        userMapper.insert(u);

        WorkerProfile p = new WorkerProfile();
        p.setUserId(u.getId());
        p.setSkillCode(skillCode);
        p.setCampusId(1L);
        p.setMaxLoad(10);
        p.setCurrentLoad(0);
        p.setOnDutyStatus(1);
        p.setRatingAvg(ratingAvg);
        p.setTotalCompletedCount(0);
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        workerProfileMapper.insert(p);
        return u.getId();
    }

    @Test
    @DisplayName("技师榜单边界：已逻辑删除用户与缺失用户行都必须回落为「师傅 {id}」且仍占位")
    public void testLeaderboardSoftDeleteAndOrphanProfile() {
        LocalDateTime now = LocalDateTime.now();
        long unique = System.nanoTime();

        // 1) 正常在册师傅
        Long activeId = insertWorker("在册师傅", "PLUMBER", new BigDecimal("4.10"));
        // 2) 已被逻辑删除的用户：榜单不得泄漏其姓名（SysUser.deleted 是 @TableLogic，
        //    原实现走 userMapper.selectById 会被自动过滤，自定义 JOIN 必须显式带上）
        Long deletedUserId = insertWorker("已删除师傅", "ELECTRICIAN", new BigDecimal("4.20"));
        jdbcTemplate.update("UPDATE sys_user SET deleted = 1 WHERE id = ?", deletedUserId);
        // 3) 档案存在但 sys_user 行缺失：INNER JOIN 会整行丢掉，必须走 LEFT JOIN 占位
        Long orphanUserId = -unique;
        WorkerProfile orphan = new WorkerProfile();
        orphan.setUserId(orphanUserId);
        orphan.setSkillCode("GENERAL");
        orphan.setCampusId(1L);
        orphan.setMaxLoad(10);
        orphan.setCurrentLoad(0);
        orphan.setOnDutyStatus(1);
        orphan.setRatingAvg(new BigDecimal("3.90"));
        orphan.setTotalCompletedCount(0);
        orphan.setCreatedAt(now);
        orphan.setUpdatedAt(now);
        workerProfileMapper.insert(orphan);

        // 给三者各挂一张本月已结单工单，确保都进榜
        insertOrder("东校区/学生生活区/1号楼", "水暖管道/爆管", "COMPLETED",
                now.minusHours(5), now.minusHours(3), activeId);
        insertOrder("东校区/学生生活区/1号楼", "水暖管道/爆管", "COMPLETED",
                now.minusHours(5), now.minusHours(3), deletedUserId);
        insertOrder("东校区/学生生活区/1号楼", "水暖管道/爆管", "COMPLETED",
                now.minusHours(5), now.minusHours(3), orphanUserId);

        Map<Long, WorkerLeaderboardVO> byUser = analyticsService.getWorkerLeaderboard(200).stream()
                .collect(Collectors.toMap(WorkerLeaderboardVO::getUserId, w -> w, (a, b) -> a));

        WorkerLeaderboardVO active = byUser.get(activeId);
        Assertions.assertNotNull(active, "在册师傅应出现在榜单");
        Assertions.assertEquals("在册师傅", active.getRealName(), "在册师傅应显示真实姓名");

        WorkerLeaderboardVO deletedUser = byUser.get(deletedUserId);
        Assertions.assertNotNull(deletedUser, "已删除用户的档案仍应占位（原实现如此）");
        Assertions.assertEquals("师傅 " + deletedUserId, deletedUser.getRealName(),
                "已逻辑删除用户的姓名不得泄漏到榜单，应回落为「师傅 {id}」");

        WorkerLeaderboardVO orphanRow = byUser.get(orphanUserId);
        Assertions.assertNotNull(orphanRow,
                "sys_user 行缺失的师傅档案必须保留（LEFT JOIN），不能被 JOIN 丢掉");
        Assertions.assertEquals("师傅 " + orphanUserId, orphanRow.getRealName(),
                "缺失用户行应回落为「师傅 {id}」");
        Assertions.assertEquals(1, orphanRow.getCompletedCount(), "孤档案的本月结单数仍应统计");
    }

    /** 构造一批覆盖各分支的数据，并返回用于参考实现的原始行（全部未删除工单）。 */
    private List<RepairOrder> seedScenarioData() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();

        Long w1 = insertWorker("等价甲师傅", "PLUMBER", new BigDecimal("4.50"));
        Long w2 = insertWorker("等价乙师傅", "ELECTRICIAN", new BigDecimal("4.50"));
        Long w3 = insertWorker("等价丙师傅", "CARPENTER", new BigDecimal("3.00"));

        // 两段路径 + 三段路径应归入同一「校区/分区」桶
        insertOrder("东校区/学生生活区/1号楼", "水暖管道/爆管", "COMPLETED",
                now.minusDays(2), now.minusDays(2).plusHours(2), w1);
        insertOrder("东校区/学生生活区", "水暖管道/爆管", "COMPLETED",
                now.minusDays(3), now.minusDays(3).plusHours(4), w1);
        // 单段路径：标签就是该段本身
        insertOrder("九龙湖校区", "电气照明/路灯", "COMPLETED",
                now.minusDays(4), now.minusDays(4).plusHours(1), w2);
        // 无斜杠类别：根节点就是整个类别名
        insertOrder("西校区/教工生活区/3号楼", "门窗锁具", "ASSIGNED",
                now.minusDays(5), null, w2);
        // 在办状态，未结单
        insertOrder("东校区/学生生活区/2号楼", "水暖管道/爆管", "IN_PROGRESS",
                now.minusMinutes(30), null, w3);
        // 闭环但远早于本月：不计入技师榜单，也不落在 30 天趋势窗口内
        // （刻意取 40 天，避开「恰好 30 天」这个同时踩窗口边界与本月初边界的歧义点）
        insertOrder("东校区/教学科研区/实验楼", "暖通空调/风机盘管", "COMPLETED",
                now.minusDays(40), now.minusDays(39), w2);
        // 闭环耗时不足 1 分钟：验证每单下限 1 分钟的口径
        insertOrder("西校区/教工生活区/3号楼", "门窗锁具/合页", "COMPLETED",
                now.minusSeconds(20), now, w1);
        // 自动核销未评分：不得计入满意度
        Long autoClosed = insertOrder("东校区/学生生活区/1号楼", "水暖管道/爆管", "COMPLETED",
                now.minusDays(6), now.minusDays(6).plusHours(72), w1);
        insertEvaluation(autoClosed, null);
        // 真实评价：进满意度
        Long rated = insertOrder("东校区/教学科研区/实验楼", "电气照明/灯具", "COMPLETED",
                now.minusDays(7), now.minusDays(7).plusHours(6), w2);
        insertEvaluation(rated, 4);
        insertEvaluation(insertOrder("西校区/后勤保障区/仓库", "电气照明/灯具", "COMPLETED",
                now.minusDays(8), now.minusDays(8).plusHours(3), w3), 2);
        // 逻辑删除单：任何统计都必须排除。@TableLogic 会拦截 updateById 对 deleted 的写入，
        // 因此这里用原生 SQL 直接把标记置为 1，才能真实覆盖「逻辑删除数据不进统计」这一分支。
        Long deletedId = insertOrder("东校区/学生生活区/9号楼", "水暖管道/爆管", "COMPLETED",
                now.minusDays(1), now.minusDays(1).plusHours(1), w1);
        jdbcTemplate.update("UPDATE repair_order SET deleted = 1 WHERE id = ?", deletedId);

        // 技师月榜数据：w1 两单（含 <1 分钟单），w2 本月一单，w3 本月零单
        Assertions.assertTrue(monthStart.isBefore(now), "本月起点应早于当前时间");

        return orderMapper.selectList(null);
    }

    // ------------------------------------------------------------------
    // 参考实现：重构前的内存聚合口径（逐字保留，勿改）
    // ------------------------------------------------------------------

    private static BigDecimal refAvgHours(List<RepairOrder> orders, BigDecimal defaultVal) {
        if (orders == null || orders.isEmpty()) {
            return defaultVal;
        }
        long totalMinutes = 0;
        for (RepairOrder o : orders) {
            totalMinutes += Math.max(1, Duration.between(o.getCreatedAt(), o.getClosedTime()).toMinutes());
        }
        double hours = (double) totalMinutes / (orders.size() * 60.0);
        return BigDecimal.valueOf(hours).setScale(2, RoundingMode.HALF_UP);
    }

    private DashboardKpiVO refKpi(List<RepairOrder> orders) {
        LocalDateTime startOfToday = LocalDate.now().atStartOfDay();
        long total = orders.size();
        long today = orders.stream()
                .filter(o -> o.getCreatedAt() != null && !o.getCreatedAt().isBefore(startOfToday)).count();
        long active = orders.stream().filter(o -> List.of("ASSIGNED", "IN_PROGRESS", "SUSPENDED")
                .contains(o.getStatus())).count();
        long completed = orders.stream().filter(o -> "COMPLETED".equals(o.getStatus())).count();

        BigDecimal rate = BigDecimal.ZERO;
        if (total > 0) {
            rate = BigDecimal.valueOf(completed).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        }

        List<RepairOrder> closed = orders.stream()
                .filter(o -> "COMPLETED".equals(o.getStatus()) && o.getClosedTime() != null && o.getCreatedAt() != null)
                .toList();
        BigDecimal avgHours = refAvgHours(closed, BigDecimal.ZERO);

        List<RepairOrderEvaluation> evals = evaluationMapper.selectList(null).stream()
                .filter(e -> e.getRatingStar() != null && e.getRatingStar() > 0).toList();
        BigDecimal score = BigDecimal.ZERO;
        if (!evals.isEmpty()) {
            double sum = evals.stream().mapToInt(RepairOrderEvaluation::getRatingStar).sum();
            score = BigDecimal.valueOf(sum / evals.size()).setScale(2, RoundingMode.HALF_UP);
        }

        return DashboardKpiVO.builder()
                .totalOrderCount(total).todayNewOrderCount(today).activeProcessingCount(active)
                .completionRatePercent(rate).averageResponseHours(avgHours).satisfactionScore(score)
                .build();
    }

    private List<SpatialHeatVO> refSpatial(List<RepairOrder> orders, int limit) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (RepairOrder o : orders) {
            String path = o.getSpaceFullPath();
            String label = SPACE_FALLBACK;
            if (path != null && !path.trim().isEmpty()) {
                String[] parts = path.split("/");
                if (parts.length >= 2) {
                    label = parts[0].trim() + "/" + parts[1].trim();
                } else {
                    label = parts[0].trim();
                }
            }
            map.put(label, map.getOrDefault(label, 0L) + 1);
        }
        return map.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit)
                .map(e -> SpatialHeatVO.builder().spaceName(e.getKey()).orderCount(e.getValue()).build())
                .toList();
    }

    private List<CategoryRatioVO> refCategory(List<RepairOrder> orders) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (RepairOrder o : orders) {
            String cat = o.getCategoryFullName();
            String root = CATEGORY_FALLBACK;
            if (cat != null && !cat.trim().isEmpty()) {
                root = cat.split("/")[0].trim();
            }
            map.put(root, map.getOrDefault(root, 0L) + 1);
        }
        return map.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> CategoryRatioVO.builder().categoryName(e.getKey()).count(e.getValue()).build())
                .toList();
    }

    private List<WorkerLeaderboardVO> refLeaderboard(int limit) {
        LocalDateTime startOfMonth = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        List<WorkerProfile> profiles = workerProfileMapper.selectList(null);
        List<WorkerLeaderboardVO> list = new ArrayList<>();
        for (WorkerProfile p : profiles) {
            SysUser user = userMapper.selectById(p.getUserId());
            String realName = user != null ? user.getRealName() : "师傅 " + p.getUserId();
            List<RepairOrder> workerOrders = orderMapper.selectList(null).stream()
                    .filter(o -> o.getWorkerId() != null && o.getWorkerId().equals(p.getUserId()))
                    .filter(o -> "COMPLETED".equals(o.getStatus()))
                    .filter(o -> o.getClosedTime() != null && o.getCreatedAt() != null)
                    .filter(o -> !o.getClosedTime().isBefore(startOfMonth))
                    .toList();
            list.add(WorkerLeaderboardVO.builder()
                    .userId(p.getUserId()).realName(realName).skillCode(p.getSkillCode())
                    .completedCount(workerOrders.size())
                    .avgHours(refAvgHours(workerOrders, BigDecimal.ZERO))
                    .ratingScore(p.getRatingAvg() != null ? p.getRatingAvg() : BigDecimal.ZERO)
                    .build());
        }
        return list.stream()
                .sorted(Comparator.comparingInt(WorkerLeaderboardVO::getCompletedCount).reversed()
                        .thenComparing(WorkerLeaderboardVO::getRatingScore, Comparator.reverseOrder()))
                .limit(limit)
                .toList();
    }

    // ------------------------------------------------------------------
    // 断言
    // ------------------------------------------------------------------

    @Test
    @DisplayName("KPI 聚合等价：SQL 条件聚合与重构前内存口径逐项一致")
    public void testDashboardKpiEquivalence() {
        List<RepairOrder> all = seedScenarioData();

        DashboardKpiVO ref = refKpi(all);
        DashboardKpiVO now = analyticsService.getDashboardKpi();

        Assertions.assertEquals(ref.getTotalOrderCount(), now.getTotalOrderCount(), "累计工单量");
        Assertions.assertEquals(ref.getTodayNewOrderCount(), now.getTodayNewOrderCount(), "今日新增量");
        Assertions.assertEquals(ref.getActiveProcessingCount(), now.getActiveProcessingCount(), "在办量");
        Assertions.assertEquals(0, ref.getCompletionRatePercent().compareTo(now.getCompletionRatePercent()), "闭环率");
        Assertions.assertEquals(0, ref.getAverageResponseHours().compareTo(now.getAverageResponseHours()), "平均时效");
        Assertions.assertEquals(0, ref.getSatisfactionScore().compareTo(now.getSatisfactionScore()), "满意度");

        // 自动核销未评分不得进满意度：(4 + 2) / 2 = 3.00
        Assertions.assertEquals(0, new BigDecimal("3.00").compareTo(now.getSatisfactionScore()),
                "未评分自动核销单不得计入满意度");
    }

    @Test
    @DisplayName("趋势聚合等价：GROUP BY 日期与内存分桶逐日一致")
    public void testTrendEquivalence() {
        seedScenarioData();
        int days = 30;
        LocalDate startDate = LocalDate.now().minusDays(days - 1);

        Map<String, Long> refSubmit = new LinkedHashMap<>();
        Map<String, Long> refCompleted = new LinkedHashMap<>();
        for (int i = 0; i < days; i++) {
            String d = startDate.plusDays(i).format(DATE_FORMATTER);
            refSubmit.put(d, 0L);
            refCompleted.put(d, 0L);
        }
        for (RepairOrder o : orderMapper.selectList(null)) {
            if (o.getCreatedAt() != null) {
                String d = o.getCreatedAt().format(DATE_FORMATTER);
                if (refSubmit.containsKey(d)) refSubmit.put(d, refSubmit.get(d) + 1);
            }
            if (o.getClosedTime() != null) {
                String d = o.getClosedTime().format(DATE_FORMATTER);
                if (refCompleted.containsKey(d)) refCompleted.put(d, refCompleted.get(d) + 1);
            }
        }

        TrendChartVO now = analyticsService.getTrendChart(days);
        Assertions.assertEquals(days, now.getDates().size());
        for (int i = 0; i < days; i++) {
            String d = now.getDates().get(i);
            Assertions.assertEquals(refSubmit.get(d), now.getSubmitCounts().get(i), "提单量 " + d);
            Assertions.assertEquals(refCompleted.get(d), now.getCompletedCounts().get(i), "结单量 " + d);
        }
    }

    @Test
    @DisplayName("空间/分类聚合等价：路径截断、单段路径、无斜杠类别与降序一致")
    public void testSpatialAndCategoryEquivalence() {
        List<RepairOrder> all = seedScenarioData();

        List<SpatialHeatVO> refHeat = refSpatial(all, 10);
        List<SpatialHeatVO> nowHeat = analyticsService.getSpatialDistribution(10);
        Assertions.assertEquals(refHeat.size(), nowHeat.size(), "空间分组数量");
        // 按「标签 -> 计数」比较，而不是按下标逐个比：原实现用 HashMap 统计，
        // 计数相同的分组之间本就没有稳定顺序，比较内容才能表达真正的等价口径
        Assertions.assertEquals(toSpaceMap(refHeat), toSpaceMap(nowHeat), "空间分组内容");
        // 两段路径与三段路径必须归并到同一个「校区/分区」桶
        // （共 4 条：「1号楼」两条 + 恰好两段的那条 + 「2号楼」一条）
        Assertions.assertTrue(nowHeat.stream().anyMatch(h ->
                        "东校区/学生生活区".equals(h.getSpaceName()) && h.getOrderCount() == 4),
                "两段与三段路径应合并为同一「校区/分区」桶");
        Assertions.assertTrue(nowHeat.stream().anyMatch(h -> "九龙湖校区".equals(h.getSpaceName())),
                "单段路径标签应为该段本身");

        List<CategoryRatioVO> refCat = refCategory(all);
        List<CategoryRatioVO> nowCat = analyticsService.getCategoryDistribution();
        Assertions.assertEquals(refCat.size(), nowCat.size(), "大类分组数量");
        Map<String, Long> refCatMap = new java.util.TreeMap<>();
        refCat.forEach(c -> refCatMap.put(c.getCategoryName(), c.getCount()));
        Map<String, Long> nowCatMap = new java.util.TreeMap<>();
        nowCat.forEach(c -> nowCatMap.put(c.getCategoryName(), c.getCount()));
        Assertions.assertEquals(refCatMap, nowCatMap, "大类分组内容");
        Assertions.assertTrue(nowCat.stream().anyMatch(c -> "门窗锁具".equals(c.getCategoryName())),
                "无斜杠类别应以整名为根节点");
    }

    @Test
    @DisplayName("技师榜单等价：JOIN 聚合、平均耗时与排序截断一致")
    public void testWorkerLeaderboardEquivalence() {
        seedScenarioData();

        List<WorkerLeaderboardVO> ref = refLeaderboard(10);
        List<WorkerLeaderboardVO> now = analyticsService.getWorkerLeaderboard(10);

        Assertions.assertEquals(ref.size(), now.size(), "榜单行数");
        for (int i = 0; i < ref.size(); i++) {
            WorkerLeaderboardVO r = ref.get(i);
            WorkerLeaderboardVO n = now.get(i);
            Assertions.assertEquals(r.getUserId(), n.getUserId(), "第 " + i + " 名师傅 ID（排序口径）");
            Assertions.assertEquals(r.getSkillCode(), n.getSkillCode());
            Assertions.assertEquals(r.getCompletedCount(), n.getCompletedCount(), "结单数 " + r.getUserId());
            Assertions.assertEquals(0, r.getAvgHours().compareTo(n.getAvgHours()), "平均耗时 " + r.getUserId());
            Assertions.assertEquals(0, r.getRatingScore().compareTo(n.getRatingScore()), "评分 " + r.getUserId());
        }

        // 本月零结单的师傅必须为 0 行数、0 耗时，而不是 null 或凭空值。
        // 用「本月结单数为 0 的行数」判断，不依赖榜单是否恰好包含零结单师傅
        // （榜单按结单量降序取前 10，零结单师傅可能被截断在榜外）
        long completedTotal = now.stream().mapToLong(WorkerLeaderboardVO::getCompletedCount).sum();
        long expectedTotal = orderMapper.selectList(null).stream()
                .filter(o -> "COMPLETED".equals(o.getStatus()))
                .filter(o -> o.getClosedTime() != null && o.getCreatedAt() != null)
                .filter(o -> !o.getClosedTime().isBefore(LocalDate.now().withDayOfMonth(1).atStartOfDay()))
                .count();
        Assertions.assertEquals(expectedTotal, completedTotal, "榜单结单数合计应等于本月真实结单数");
        now.stream().filter(w -> w.getCompletedCount() == 0)
                .forEach(w -> Assertions.assertEquals(0, BigDecimal.ZERO.compareTo(w.getAvgHours()),
                        "零结单师傅耗时必须为 0：" + w.getRealName()));
    }

    @Test
    @DisplayName("小样本闭式核对：新库零种子下 SQL 聚合仍返回真实值而非 0")
    public void testSmallDatasetExactValues() {
        LocalDateTime now = LocalDateTime.now();

        Long workerId = insertWorker("闭式核对师傅", "PLUMBER", new BigDecimal("4.25"));
        insertOrder("东校区/学生生活区/1号楼", "水暖管道/爆管", "COMPLETED",
                now.minusHours(3), now.minusHours(1), workerId);
        Long rated = insertOrder("东校区/学生生活区/2号楼", "水暖管道/阀门", "ASSIGNED",
                now.minusMinutes(10), null, workerId);
        insertEvaluation(rated, 4);

        // 统计口径限定在本次插入的两条上（测试库为共享内存库，可能残留其他用例数据）
        List<RepairOrder> mine = orderMapper.selectList(null).stream()
                .filter(o -> workerId.equals(o.getWorkerId())).toList();
        Assertions.assertEquals(2, mine.size());

        BigDecimal expectedAvgHours = refAvgHours(
                mine.stream().filter(o -> "COMPLETED".equals(o.getStatus())).toList(), BigDecimal.ZERO);

        WorkerLeaderboardVO row = analyticsService.getWorkerLeaderboard(50).stream()
                .filter(w -> workerId.equals(w.getUserId())).findFirst().orElseThrow();
        Assertions.assertEquals(1, row.getCompletedCount(), "本月结单数应为 1，若为 0 说明 JOIN 聚合未命中");
        Assertions.assertEquals(0, expectedAvgHours.compareTo(row.getAvgHours()),
                "闭式核对：平均耗时应为 " + expectedAvgHours + " 小时（实际 " + row.getAvgHours() + "）");
        Assertions.assertEquals(0, new BigDecimal("4.25").compareTo(row.getRatingScore()));

        // 空间标签与故障大类根节点的截断必须与参考实现一致
        String expectedLabel = refSpatial(mine, 10).get(0).getSpaceName();
        Assertions.assertEquals("东校区/学生生活区", expectedLabel);
        long heatCount = analyticsService.getSpatialDistribution(50).stream()
                .filter(h -> expectedLabel.equals(h.getSpaceName()))
                .mapToLong(SpatialHeatVO::getOrderCount).findFirst().orElse(0L);
        Assertions.assertTrue(heatCount >= 2, "空间聚合应以「" + expectedLabel + "」为标签命中本次数据");
        Assertions.assertTrue(analyticsService.getCategoryDistribution().stream()
                        .anyMatch(c -> "水暖管道".equals(c.getCategoryName()) && c.getCount() >= 2),
                "故障大类应以「水暖管道」为根节点聚合");
    }

    @Test
    @DisplayName("聚合数值准确性：用字面期望值核对 KPI 与趋势的算术，不依赖任何参考实现")
    public void testLiteralAggregateValues() {
        LocalDateTime now = LocalDateTime.now();

        Long approved = insertWorker("字面核对师傅", "PLUMBER", new BigDecimal("4.00"));
        // 已结单：闭环耗时 2 小时 → 平均时效应为 2.00
        insertOrder("东校区/学生生活区/1号楼", "水暖管道/爆管", "COMPLETED",
                now.minusHours(3), now.minusHours(1), approved);
        // 在办：进 activeProcessingCount，不进平均时效
        insertOrder("东校区/学生生活区/2号楼", "水暖管道/阀门", "ASSIGNED",
                now.minusMinutes(20), null, approved);
        // 未结单：只进今日新增
        insertOrder("东校区/教学科研区/实验楼", "电气照明/灯具", "PENDING_DISPATCH",
                now.minusMinutes(5), null, null);

        DashboardKpiVO kpi = analyticsService.getDashboardKpi();

        // 测试库为共享内存库，可能有其他用例残留 → 对全局计数用「至少」下界。
        // 但比率类指标可以精确核对：闭环率的定义就是「全局 COMPLETED 数 / 全局总数」。
        Integer rawTotal = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM repair_order WHERE deleted = 0", Integer.class);
        Integer rawCompleted = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM repair_order WHERE deleted = 0 AND status = 'COMPLETED'", Integer.class);
        Assertions.assertTrue(kpi.getTotalOrderCount() >= 3, "累计工单量下界");
        Assertions.assertTrue(kpi.getTodayNewOrderCount() >= 3, "今日新增下界");
        Assertions.assertTrue(kpi.getActiveProcessingCount() >= 1, "在办量下界");
        Assertions.assertEquals(rawTotal.longValue(), kpi.getTotalOrderCount(),
                "累计工单量必须等于库中未删除工单数（独立 SQL 核对，不依赖参考实现）");
        BigDecimal expectedCompletionRate = BigDecimal.valueOf(rawCompleted)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(rawTotal), 2, RoundingMode.HALF_UP);
        Assertions.assertEquals(0, expectedCompletionRate.compareTo(kpi.getCompletionRatePercent()),
                "闭环率必须是独立算出的 " + expectedCompletionRate + "%，实际 " + kpi.getCompletionRatePercent());
        // 趋势：本次插入的 3 张单全在今日 → 今日提单量至少 3
        TrendChartVO trend = analyticsService.getTrendChart(30);
        String today = LocalDate.now().format(DATE_FORMATTER);
        int idx = trend.getDates().indexOf(today);
        Assertions.assertTrue(idx >= 0, "趋势窗口应包含今天");
        Assertions.assertTrue(trend.getSubmitCounts().get(idx) >= 3, "今日提单量下界");
        Assertions.assertTrue(trend.getCompletedCounts().get(idx) >= 1, "今日结单量下界");

        // 平均时效：本次唯一已结单工单闭环 2 小时，全局均值必须为正且有限
        Assertions.assertTrue(kpi.getAverageResponseHours().compareTo(BigDecimal.ZERO) > 0,
                "有已结单数据时平均时效应大于 0，而不是伪造的 0 或兜底值");

        // 空间热力：字面断言该标签确实聚合到本次插入的两张单
        Map<String, Long> heat = analyticsService.getSpatialDistribution(50).stream()
                .collect(Collectors.toMap(SpatialHeatVO::getSpaceName, SpatialHeatVO::getOrderCount, (a, b) -> a));
        Assertions.assertEquals(2L, heat.getOrDefault("东校区/学生生活区", 0L),
                "「东校区/学生生活区」应精确聚合 2 张工单（1 张三段路径 + 1 张两段路径）");

        // 故障大类：字面断言根节点
        Map<String, Long> cats = analyticsService.getCategoryDistribution().stream()
                .collect(Collectors.toMap(CategoryRatioVO::getCategoryName, CategoryRatioVO::getCount, (a, b) -> a));
        Assertions.assertEquals(2L, cats.getOrDefault("水暖管道", 0L), "「水暖管道」应聚合 2 张");
        Assertions.assertEquals(1L, cats.getOrDefault("电气照明", 0L), "「电气照明」应聚合 1 张");
    }
}
