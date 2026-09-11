package com.campus.repair.modules.analytics.mapper;

import com.campus.repair.modules.analytics.dto.CategoryRatioRow;
import com.campus.repair.modules.analytics.dto.DailyTrendRow;
import com.campus.repair.modules.analytics.dto.DashboardAggregateRow;
import com.campus.repair.modules.analytics.dto.SpatialHeatRow;
import com.campus.repair.modules.analytics.dto.WorkerLeaderboardRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 分析模块自有的汇总读模型（Read Model）。
 *
 * <p>本接口是 {@code analytics} 模块**自己的**持久层，只做只读聚合，不承载任何写操作。
 * 它取代了此前 {@code AnalyticsServiceImpl} 直接注入 4 个异构模块 Mapper
 * （{@code RepairOrderMapper} / {@code RepairOrderEvaluationMapper} /
 * {@code WorkerProfileMapper} / {@code SysUserMapper}）的做法——那种写法违反了
 * 《实现计划》§3.2「各业务垂直模块严禁跨模块直接穿透持久层 Mapper」的约束。
 *
 * <p>与之同时，原先在 JVM 内 `selectList` 全表 + `split("/")` + `HashMap` 计数的
 * 内存聚合，已全部下推为 SQL `GROUP BY` / 条件聚合（CASE WHEN），
 * 避免历史数据到万级时的堆内存与 GC 压力。
 *
 * <p>语义约定：所有口径必须与重构前逐项等价——
 * <ul>
 *   <li>一律带 {@code deleted = 0} 逻辑删除过滤；</li>
 *   <li>闭环耗时的每单下限为 1 分钟（沿用原 {@code Math.max(1, ...)} 口径），
 *       无耗时的订单不进入平均耗时分母；</li>
 *   <li>满意度只统计 {@code rating_star} 非空且大于 0 的真实评价，
 *       自动核销产生的未评分记录不得计入（见 AGENTS.md §3）。</li>
 * </ul>
 *
 * <p>SQL 需同时兼容 MySQL 8 与测试使用的 H2（MySQL 兼容模式），因此刻意绕开两个不可移植点：
 * <ul>
 *   <li>日期分桶用标准 {@code CAST(ts AS DATE)}，<b>不用 {@code DATE_FORMAT}</b>
 *       （H2 即使 {@code MODE=MySQL} 也不提供该函数）；</li>
 *   <li>「校区/分区」标签的字符串截断<b>不写进 SQL</b>，只在 Java 侧对去重后的路径做
 *       {@code split("/")}（H2 对 {@code LOCATE} 的表达式求值语义不可靠，
 *       详见 {@code AGENTS.md} §4 首条）。</li>
 * </ul>
 */
@Mapper
public interface AnalyticsReportMapper {

    /**
     * 大屏顶层 KPI：一次条件聚合取回全部计数与求和，替代原先的 4 次 count + 全量拉取。
     */
    @Select("SELECT " +
            "  COUNT(*) AS \"totalOrders\", " +
            "  COALESCE(SUM(CASE WHEN created_at >= #{todayStart} THEN 1 ELSE 0 END), 0) AS \"todayOrders\", " +
            "  COALESCE(SUM(CASE WHEN status IN ('ASSIGNED', 'IN_PROGRESS', 'SUSPENDED') THEN 1 ELSE 0 END), 0) AS \"activeOrders\", " +
            "  COALESCE(SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END), 0) AS \"completedOrders\", " +
            "  COALESCE(SUM(CASE WHEN status = 'COMPLETED' AND created_at IS NOT NULL AND closed_time IS NOT NULL THEN 1 ELSE 0 END), 0) AS \"timedOrders\", " +
            "  COALESCE(SUM(CASE WHEN status = 'COMPLETED' AND created_at IS NOT NULL AND closed_time IS NOT NULL " +
            "                    THEN CASE WHEN TIMESTAMPDIFF(MINUTE, created_at, closed_time) < 1 THEN 1 " +
            "                              ELSE TIMESTAMPDIFF(MINUTE, created_at, closed_time) END ELSE 0 END), 0) AS \"totalMinutes\", " +
            "  (SELECT COUNT(*) FROM repair_order_evaluation " +
            "     WHERE rating_star IS NOT NULL AND rating_star > 0) AS \"evalCount\", " +
            "  (SELECT COALESCE(SUM(rating_star), 0) FROM repair_order_evaluation " +
            "     WHERE rating_star IS NOT NULL AND rating_star > 0) AS \"evalStarSum\" " +
            "FROM repair_order WHERE deleted = 0")
    DashboardAggregateRow selectDashboardAggregate(@Param("todayStart") LocalDateTime todayStart);

    /**
     * 趋势图：按日 GROUP BY 出提单量与结单量（结单侧限定同一时间窗，与原实现一致）。
     * 日期用标准 {@code CAST(... AS DATE)} 而非 {@code DATE_FORMAT}，以同时兼容 MySQL 与 H2。
     */
    @Select("SELECT CAST(created_at AS DATE) AS \"statDate\", " +
            "       COUNT(*) AS \"submitCount\", " +
            "       0 AS \"completedCount\" " +
            "FROM repair_order " +
            "WHERE deleted = 0 AND created_at >= #{rangeStart} " +
            "GROUP BY CAST(created_at AS DATE) " +
            "ORDER BY \"statDate\"")
    List<DailyTrendRow> selectDailySubmitCounts(@Param("rangeStart") LocalDateTime rangeStart);

    @Select("SELECT CAST(closed_time AS DATE) AS \"statDate\", " +
            "       0 AS \"submitCount\", " +
            "       COUNT(*) AS \"completedCount\" " +
            "FROM repair_order " +
            "WHERE deleted = 0 AND created_at >= #{rangeStart} AND closed_time IS NOT NULL " +
            "GROUP BY CAST(closed_time AS DATE) " +
            "ORDER BY \"statDate\"")
    List<DailyTrendRow> selectDailyCompletedCounts(@Param("rangeStart") LocalDateTime rangeStart);

    /**
     * 空间热力：按 {@code space_full_path} 原值分组计数，返回条数最多的前 N 条原始路径。
     *
     * <p><b>为什么不在 SQL 里直接截断成「校区/分区」标签</b>：H2（测试库）对
     * {@code LOCATE(子串, 表达式)} 的求值不稳定——参数是<b>列</b>时返回 0，
     * 参数是同一列的<b>表达式</b>时却返回搜索起始位置，导致「恰好两段」的路径被误判为三段
     * （「东校区/学生生活区」被截成「东校区」）。用 MyBatis 与 JdbcTemplate 跑同一条 SQL
     * 都能得到不同结果，属数据库层不可控行为。
     *
     * <p>因此这里只让数据库做它擅长的事：<b>按原始列做 GROUP BY 计数</b>
     * （不再是全表拉进 JVM 逐行统计），标签的字符串截断交给上层对
     * 「去重后的路径集合」完成——去重后条数远小于工单量，既消除了内存聚合瓶颈，
     * 也保证了跨库语义与重构前完全一致。
     *
     * <p><b>刻意不加 LIMIT</b>：多个原始路径可能归并到同一个「校区/分区」标签，
     * 若在 SQL 侧先截断，返回的分组会比重构前少（等价性测试抓到过这个偏差）。
     * limit 必须在上层归并出标签之后再施加。GROUP BY 已把返回行数压到
     * 「去重路径数」量级，全量返回也不构成内存压力。
     */
    @Select("SELECT space_full_path AS \"spacePath\", COUNT(*) AS \"orderCount\" " +
            "FROM repair_order " +
            "WHERE deleted = 0 " +
            "GROUP BY space_full_path " +
            "ORDER BY COUNT(*) DESC, space_full_path ASC")
    List<SpatialHeatRow> selectTopSpacePaths();

    /**
     * 故障大类占比：按 {@code category_full_name} 原值分组计数。
     *
     * <p>与空间热力同理（见 {@link #selectTopSpacePaths()}），
     * 根节点的字符串截断交给上层对「去重后的类别集合」完成——那一步只是纯字符串取首段，
     * 没有数据量问题，也不会踩库函数求值不一致的坑。
     */
    @Select("SELECT category_full_name AS \"categoryPath\", COUNT(*) AS \"orderCount\" " +
            "FROM repair_order " +
            "WHERE deleted = 0 " +
            "GROUP BY category_full_name " +
            "ORDER BY COUNT(*) DESC, category_full_name ASC")
    List<CategoryRatioRow> selectCategoryPathCounts();

    /**
     * 技师月度战绩：worker_profile 与 sys_user 在数据库侧一次 JOIN + 聚合，
     * 取代原先「逐个师傅查一次用户 + 查一次工单」的 N+1 查询。
     *
     * <p><b>两个必须保持的边界（与重构前的 MyBatis-Plus 语义对齐）</b>：
     * <ul>
     *   <li>用 <b>LEFT JOIN</b> 而非 INNER JOIN：原实现先遍历全部 {@code worker_profile}，
     *       用户行缺失或已删除时以「师傅 {id}」兜底占位；INNER JOIN 会把这些档案整行丢掉，
     *       榜单会少人。</li>
     *   <li>JOIN 条件必须显式带 {@code u.deleted = 0}：{@code SysUser.deleted} 是
     *       {@code @TableLogic}，原实现走 {@code userMapper.selectById()} 会被自动追加该过滤；
     *       自定义 SQL 不会，漏了就会把已删除用户的姓名泄漏到榜单里。</li>
     * </ul>
     */
    @Select("SELECT wp.user_id AS \"userId\", " +
            "       u.real_name AS \"realName\", " +
            "       wp.skill_code AS \"skillCode\", " +
            "       COUNT(ro.id) AS \"completedCount\", " +
            "       COALESCE(SUM(CASE WHEN ro.id IS NOT NULL " +
            "                         THEN CASE WHEN TIMESTAMPDIFF(MINUTE, ro.created_at, ro.closed_time) < 1 THEN 1 " +
            "                                   ELSE TIMESTAMPDIFF(MINUTE, ro.created_at, ro.closed_time) END " +
            "                         ELSE 0 END), 0) AS \"totalMinutes\", " +
            "       wp.rating_avg AS \"ratingAvg\" " +
            "FROM worker_profile wp " +
            "LEFT JOIN sys_user u ON u.id = wp.user_id AND u.deleted = 0 " +
            "LEFT JOIN repair_order ro ON ro.worker_id = wp.user_id " +
            "  AND ro.deleted = 0 " +
            "  AND ro.status = 'COMPLETED' " +
            "  AND ro.closed_time IS NOT NULL " +
            "  AND ro.created_at IS NOT NULL " +
            "  AND ro.closed_time >= #{startOfMonth} " +
            "GROUP BY wp.user_id, u.real_name, wp.skill_code, wp.rating_avg")
    List<WorkerLeaderboardRow> selectWorkerLeaderboardRows(@Param("startOfMonth") LocalDateTime startOfMonth);
}
