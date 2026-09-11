package com.campus.repair.modules.analytics.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.repair.modules.analytics.dto.CategoryRatioRow;
import com.campus.repair.modules.analytics.dto.DailyTrendRow;
import com.campus.repair.modules.analytics.dto.DashboardAggregateRow;
import com.campus.repair.modules.analytics.dto.SpatialHeatRow;
import com.campus.repair.modules.analytics.dto.WorkOrderFilterQueryRequest;
import com.campus.repair.modules.analytics.dto.WorkerLeaderboardRow;
import com.campus.repair.modules.analytics.mapper.AnalyticsReportMapper;
import com.campus.repair.modules.analytics.vo.*;
import com.campus.repair.modules.workorder.entity.RepairOrder;
import com.campus.repair.modules.workorder.entity.RepairOrderEvaluation;
import com.campus.repair.modules.workorder.enums.OrderStatusEnum;
import com.campus.repair.modules.workorder.mapper.RepairOrderEvaluationMapper;
import com.campus.repair.modules.workorder.mapper.RepairOrderMapper;
import com.campus.repair.modules.workorder.vo.DispatchOrderItemVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsServiceImpl implements AnalyticsService {

    private final RepairOrderMapper orderMapper;
    private final RepairOrderEvaluationMapper evaluationMapper;
    /**
     * 分析模块自有的汇总读模型。全部分析口径的聚合都在该 Mapper 内以 SQL 完成，
     * 不再穿透 user / workorder 模块的 Mapper，也不再把全表拉进 JVM 做内存聚合。
     */
    private final AnalyticsReportMapper reportMapper;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 把「分钟合计 / 单数」换算成小时，保留 2 位小数（与原内存口径完全一致：
     * 每单闭环耗时下限 1 分钟，由 SQL 侧的 CASE WHEN 保证）。
     * KPI 平均时效与技师榜单平均耗时共用此方法，确保两条口径不会各自漂移。
     */
    private static BigDecimal avgHoursFromMinutes(Long timedOrders, Long totalMinutes) {
        if (timedOrders == null || timedOrders <= 0 || totalMinutes == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(totalMinutes)
                .divide(BigDecimal.valueOf(timedOrders).multiply(BigDecimal.valueOf(60)), 2, RoundingMode.HALF_UP);
    }

    private static String getStatusChineseDesc(String status) {
        try {
            return OrderStatusEnum.valueOf(status).getDesc();
        } catch (Exception e) {
            return status;
        }
    }

    @Override
    public DashboardKpiVO getDashboardKpi() {
        LocalDateTime startOfToday = LocalDate.now().atStartOfDay();

        // 一次条件聚合取回全部计数与求和（原实现为 4 次 count + 2 次全量拉取）
        DashboardAggregateRow agg = reportMapper.selectDashboardAggregate(startOfToday);

        long totalOrders = safeLong(agg.getTotalOrders());
        long todayOrders = safeLong(agg.getTodayOrders());
        long activeOrders = safeLong(agg.getActiveOrders());
        long completedOrders = safeLong(agg.getCompletedOrders());

        BigDecimal completionRate = BigDecimal.ZERO;
        if (totalOrders > 0) {
            completionRate = BigDecimal.valueOf(completedOrders)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(totalOrders), 2, RoundingMode.HALF_UP);
        }

        BigDecimal avgHours = avgHoursFromMinutes(agg.getTimedOrders(), agg.getTotalMinutes());

        // 综合满意度均分：只统计真实用户打分，排除未评分与自动核销无分数据；
        // 无任何有效评价时保持 0（前端渲染为“—”），不得用虚增值充当真实口碑
        long evalCount = safeLong(agg.getEvalCount());
        BigDecimal avgScore = BigDecimal.ZERO;
        if (evalCount > 0) {
            avgScore = BigDecimal.valueOf(safeLong(agg.getEvalStarSum()))
                    .divide(BigDecimal.valueOf(evalCount), 2, RoundingMode.HALF_UP);
        }

        return DashboardKpiVO.builder()
                .totalOrderCount(totalOrders)
                .todayNewOrderCount(todayOrders)
                .activeProcessingCount(activeOrders)
                .completionRatePercent(completionRate)
                .averageResponseHours(avgHours)
                .satisfactionScore(avgScore)
                .build();
    }

    @Override
    public TrendChartVO getTrendChart(int days) {
        if (days <= 0 || days > 90) {
            days = 30;
        }

        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days - 1);

        List<String> dates = new ArrayList<>();
        Map<String, Long> submitMap = new HashMap<>();
        Map<String, Long> completedMap = new HashMap<>();

        for (int i = 0; i < days; i++) {
            String d = startDate.plusDays(i).format(DATE_FORMATTER);
            dates.add(d);
            submitMap.put(d, 0L);
            completedMap.put(d, 0L);
        }

        LocalDateTime rangeStart = startDate.atStartOfDay();

        // 数据库侧按日 GROUP BY；日期先映射为 LocalDate 再格式化，避免依赖 DATE_FORMAT
        for (DailyTrendRow row : reportMapper.selectDailySubmitCounts(rangeStart)) {
            if (row.getStatDate() == null || row.getSubmitCount() == null) {
                continue;
            }
            String d = row.getStatDate().format(DATE_FORMATTER);
            if (submitMap.containsKey(d)) {
                submitMap.put(d, row.getSubmitCount());
            }
        }
        for (DailyTrendRow row : reportMapper.selectDailyCompletedCounts(rangeStart)) {
            if (row.getStatDate() == null || row.getCompletedCount() == null) {
                continue;
            }
            String d = row.getStatDate().format(DATE_FORMATTER);
            if (completedMap.containsKey(d)) {
                completedMap.put(d, row.getCompletedCount());
            }
        }

        List<Long> submitList = dates.stream().map(submitMap::get).toList();
        List<Long> completedList = dates.stream().map(completedMap::get).toList();

        return TrendChartVO.builder()
                .dates(dates)
                .submitCounts(submitList)
                .completedCounts(completedList)
                .build();
    }

    /** 空间路径缺失时的兜底标签，与重构前口径一致 */
    private static final String SPACE_FALLBACK = "其他空间";

    /**
     * 由空间全路径取「校区/分区」标签（前两段），与重构前的 split("/") 口径逐字一致。
     */
    private static String spaceLabel(String path) {
        if (!StringUtils.hasText(path)) {
            return SPACE_FALLBACK;
        }
        String[] parts = path.split("/");
        if (parts.length >= 2) {
            return parts[0].trim() + "/" + parts[1].trim();
        }
        return parts[0].trim();
    }

    @Override
    public List<SpatialHeatVO> getSpatialDistribution(int limit) {
        if (limit <= 0) limit = 10;

        // 数据库按原始路径 GROUP BY 计数（取代原先拉全表进内存逐行统计）；
        // 标签截断只需在「去重后的路径集合」上做，规模远小于工单量。
        // limit 必须在归并出标签之后再施加（多条原始路径可能归入同一标签）
        Map<String, Long> map = new LinkedHashMap<>();
        for (SpatialHeatRow row : reportMapper.selectTopSpacePaths()) {
            String label = spaceLabel(row.getSpacePath());
            map.merge(label, safeLong(row.getOrderCount()), Long::sum);
        }

        return map.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(e -> SpatialHeatVO.builder()
                        .spaceName(e.getKey())
                        .orderCount(e.getValue())
                        .build())
                .toList();
    }

    /** 故障类别路径缺失时的兜底大类，与重构前口径一致 */
    private static final String CATEGORY_FALLBACK = "综合报修";

    /**
     * 由故障类别全路径取根大类（首段），与重构前的 split("/") 口径逐字一致。
     */
    private static String categoryLabel(String path) {
        if (!StringUtils.hasText(path)) {
            return CATEGORY_FALLBACK;
        }
        return path.split("/")[0].trim();
    }

    @Override
    public List<CategoryRatioVO> getCategoryDistribution() {
        // 数据库按原始类别路径 GROUP BY 计数，首段归并只需在去重后的类别集合上完成
        Map<String, Long> map = new LinkedHashMap<>();
        for (CategoryRatioRow row : reportMapper.selectCategoryPathCounts()) {
            map.merge(categoryLabel(row.getCategoryPath()), safeLong(row.getOrderCount()), Long::sum);
        }

        return map.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .map(e -> CategoryRatioVO.builder()
                        .categoryName(e.getKey())
                        .count(e.getValue())
                        .build())
                .toList();
    }

    @Override
    public List<WorkerLeaderboardVO> getWorkerLeaderboard(int limit) {
        if (limit <= 0) limit = 10;

        // 统计自然月周期内（本月1号起）的战绩
        LocalDateTime startOfMonth = LocalDate.now().withDayOfMonth(1).atStartOfDay();

        // 师傅档案、用户姓名与本月结单聚合在数据库侧一次 JOIN 完成（原实现为逐师傅 N+1 查询）
        return reportMapper.selectWorkerLeaderboardRows(startOfMonth).stream()
                .map(row -> WorkerLeaderboardVO.builder()
                        .userId(row.getUserId())
                        .realName(row.getRealName() != null ? row.getRealName() : "师傅 " + row.getUserId())
                        .skillCode(row.getSkillCode())
                        .completedCount(row.getCompletedCount() != null ? row.getCompletedCount() : 0)
                        .avgHours(avgHoursFromMinutes(
                                row.getCompletedCount() == null ? 0L : row.getCompletedCount().longValue(),
                                row.getTotalMinutes()))
                        // 技师档案无历史评分时保持 0，避免榜单出现凭空生成的星级
                        .ratingScore(row.getRatingAvg() != null ? row.getRatingAvg() : BigDecimal.ZERO)
                        .build())
                // 按结单量降序排，再按评分降序排（与原实现同序，保证 limit 截断结果稳定）
                .sorted(Comparator.comparingInt(WorkerLeaderboardVO::getCompletedCount).reversed()
                        .thenComparing(WorkerLeaderboardVO::getRatingScore, Comparator.reverseOrder()))
                .limit(limit)
                .toList();
    }

    @Override
    public Page<DispatchOrderItemVO> queryOrdersWithFilter(WorkOrderFilterQueryRequest request) {
        int pageNo = request.getPage() != null && request.getPage() > 0 ? request.getPage() : 1;
        int pageSize = request.getSize() != null && request.getSize() > 0 ? request.getSize() : 20;

        LambdaQueryWrapper<RepairOrder> query = buildFilterQuery(request);
        query.orderByDesc(RepairOrder::getId);

        Page<RepairOrder> page = orderMapper.selectPage(new Page<>(pageNo, pageSize), query);

        List<DispatchOrderItemVO> voList = page.getRecords().stream().map(order -> {
            DispatchOrderItemVO vo = new DispatchOrderItemVO();
            BeanUtils.copyProperties(order, vo);
            return vo;
        }).collect(Collectors.toList());

        Page<DispatchOrderItemVO> resultPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        resultPage.setRecords(voList);
        return resultPage;
    }

    @Override
    public List<WorkOrderExportVO> getOrdersForExport(WorkOrderFilterQueryRequest request) {
        LambdaQueryWrapper<RepairOrder> query = buildFilterQuery(request);
        query.orderByDesc(RepairOrder::getId);

        List<RepairOrder> orders = orderMapper.selectList(query);
        List<WorkOrderExportVO> result = new ArrayList<>();

        // 预加载已结单工单的评价记录，批量映射满意度星级
        List<Long> orderIds = orders.stream().map(RepairOrder::getId).filter(Objects::nonNull).toList();
        Map<Long, RepairOrderEvaluation> evalMap = new HashMap<>();
        if (!orderIds.isEmpty()) {
            List<RepairOrderEvaluation> evals = evaluationMapper.selectList(
                    new LambdaQueryWrapper<RepairOrderEvaluation>().in(RepairOrderEvaluation::getOrderId, orderIds)
            );
            for (RepairOrderEvaluation ev : evals) {
                evalMap.put(ev.getOrderId(), ev);
            }
        }

        for (RepairOrder o : orders) {
            String statusDesc = getStatusChineseDesc(o.getStatus());
            String urgencyDesc = (o.getUrgencyLevel() != null && o.getUrgencyLevel() == 2) ? "【加急】" : "常规";
            String archiveDesc = (o.getIsArchived() != null && o.getIsArchived() == 1) ? "已归档冷数据" : "活跃业务数据";

            String ratingDesc = "-";
            RepairOrderEvaluation ev = evalMap.get(o.getId());
            if (ev != null && ev.getRatingStar() != null && ev.getRatingStar() > 0) {
                ratingDesc = ev.getRatingStar() + " 星";
            } else if (ev != null && ev.getIsAutoConfirmed() != null && ev.getIsAutoConfirmed() == 1) {
                ratingDesc = "自动结单(未评分)";
            }

            result.add(WorkOrderExportVO.builder()
                    .orderNo(o.getOrderNo())
                    .statusDesc(statusDesc)
                    .urgencyDesc(urgencyDesc)
                    .spaceFullPath(o.getSpaceFullPath())
                    .categoryFullName(o.getCategoryFullName())
                    .description(o.getDescription())
                    .applicantName(o.getApplicantName())
                    .applicantPhone(o.getApplicantPhone())
                    .appointmentPeriod(o.getAppointmentPeriod())
                    .workerName(o.getWorkerName() != null ? o.getWorkerName() : "-")
                    .materialCost(o.getMaterialCost() != null ? o.getMaterialCost() : BigDecimal.ZERO)
                    .ratingStarDesc(ratingDesc)
                    .reworkCount(o.getReworkCount() != null ? o.getReworkCount() : 0)
                    .archiveDesc(archiveDesc)
                    .createdAt(o.getCreatedAt() != null ? o.getCreatedAt().format(DATETIME_FORMATTER) : "")
                    .closedTime(o.getClosedTime() != null ? o.getClosedTime().format(DATETIME_FORMATTER) : "-")
                    .build());
        }

        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int archiveHistoricalOrders(int days) {
        if (days <= 0) days = 180;
        LocalDateTime threshold = LocalDateTime.now().minusDays(days);

        // 将创建时间或完工时间在 threshold 之前且处于已结单状态的工单标记为归档冷数据
        int updated = orderMapper.update(null, new LambdaUpdateWrapper<RepairOrder>()
                .set(RepairOrder::getIsArchived, 1)
                .eq(RepairOrder::getStatus, OrderStatusEnum.COMPLETED.name())
                .eq(RepairOrder::getIsArchived, 0)
                .le(RepairOrder::getClosedTime, threshold));

        log.info("[数据归档引擎] 成功归档 {} 张结单超过 {} 天的历史冷数据工单", updated, days);
        return updated;
    }

    private static long safeLong(Long value) {
        return value != null ? value : 0L;
    }

    private LambdaQueryWrapper<RepairOrder> buildFilterQuery(WorkOrderFilterQueryRequest request) {
        LambdaQueryWrapper<RepairOrder> query = new LambdaQueryWrapper<>();
        query.eq(RepairOrder::getDeleted, 0);

        if (request.getIsArchived() != null && request.getIsArchived() >= 0) {
            query.eq(RepairOrder::getIsArchived, request.getIsArchived());
        }

        if (StringUtils.hasText(request.getKeyword())) {
            String kw = request.getKeyword().trim();
            query.and(w -> w.like(RepairOrder::getOrderNo, kw)
                    .or().like(RepairOrder::getDescription, kw)
                    .or().like(RepairOrder::getApplicantName, kw)
                    .or().like(RepairOrder::getApplicantPhone, kw)
                    .or().like(RepairOrder::getWorkerName, kw)
                    .or().like(RepairOrder::getSpaceFullPath, kw));
        }

        if (request.getSpaceId() != null) {
            query.eq(RepairOrder::getSpaceId, request.getSpaceId());
        }

        if (request.getCategoryId() != null) {
            query.eq(RepairOrder::getCategoryId, request.getCategoryId());
        }

        if (request.getWorkerId() != null) {
            query.eq(RepairOrder::getWorkerId, request.getWorkerId());
        }

        if (request.getStatuses() != null && !request.getStatuses().isEmpty()) {
            query.in(RepairOrder::getStatus, request.getStatuses());
        }

        if (request.getIsUrgent() != null) {
            query.eq(RepairOrder::getUrgencyLevel, request.getIsUrgent() == 1 ? 2 : 1);
        }

        if (StringUtils.hasText(request.getCreateTimeStart())) {
            try {
                LocalDateTime start = LocalDateTime.parse(request.getCreateTimeStart().trim(), DATETIME_FORMATTER);
                query.ge(RepairOrder::getCreatedAt, start);
            } catch (Exception e) {
                try {
                    LocalDateTime start = LocalDate.parse(request.getCreateTimeStart().trim(), DATE_FORMATTER).atStartOfDay();
                    query.ge(RepairOrder::getCreatedAt, start);
                } catch (Exception ignored) {}
            }
        }

        if (StringUtils.hasText(request.getCreateTimeEnd())) {
            try {
                LocalDateTime end = LocalDateTime.parse(request.getCreateTimeEnd().trim(), DATETIME_FORMATTER);
                query.le(RepairOrder::getCreatedAt, end);
            } catch (Exception e) {
                try {
                    LocalDateTime end = LocalDate.parse(request.getCreateTimeEnd().trim(), DATE_FORMATTER).atTime(23, 59, 59);
                    query.le(RepairOrder::getCreatedAt, end);
                } catch (Exception ignored) {}
            }
        }

        return query;
    }
}
