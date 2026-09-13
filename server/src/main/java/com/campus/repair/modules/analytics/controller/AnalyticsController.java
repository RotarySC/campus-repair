package com.campus.repair.modules.analytics.controller;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.repair.common.result.R;
import com.campus.repair.modules.analytics.dto.WorkOrderFilterQueryRequest;
import com.campus.repair.modules.analytics.service.AnalyticsService;
import com.campus.repair.modules.analytics.vo.*;
import com.campus.repair.modules.workorder.vo.DispatchOrderItemVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Tag(name = "运营大屏与高级分析报表", description = "指挥大屏核心KPI、双折线趋势、空间热力、工单高级检索流式导出与归档")
@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @Operation(summary = "获取后勤运营指挥大屏顶层 6 大核心 KPI 汇总指标")
    @GetMapping("/dashboard-kpi")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN', 'DISPATCHER')")
    public R<DashboardKpiVO> getDashboardKpi() {
        return R.success(analyticsService.getDashboardKpi());
    }

    @Operation(summary = "获取近 N 天提单量与结单量双折线趋势")
    @GetMapping("/trend-chart")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN', 'DISPATCHER')")
    public R<TrendChartVO> getTrendChart(@RequestParam(defaultValue = "30") Integer days) {
        return R.success(analyticsService.getTrendChart(days));
    }

    @Operation(summary = "获取各校区与空间建筑故障频次热力排行")
    @GetMapping("/spatial-distribution")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN', 'DISPATCHER')")
    public R<List<SpatialHeatVO>> getSpatialDistribution(@RequestParam(defaultValue = "10") Integer limit) {
        return R.success(analyticsService.getSpatialDistribution(limit));
    }

    @Operation(summary = "获取故障大类结构占比分布")
    @GetMapping("/category-pie")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN', 'DISPATCHER')")
    public R<List<CategoryRatioVO>> getCategoryDistribution() {
        return R.success(analyticsService.getCategoryDistribution());
    }

    @Operation(summary = "获取维修师傅月度战绩排行榜")
    @GetMapping("/worker-ranking")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN', 'DISPATCHER')")
    public R<List<WorkerLeaderboardVO>> getWorkerLeaderboard(@RequestParam(defaultValue = "10") Integer limit) {
        return R.success(analyticsService.getWorkerLeaderboard(limit));
    }

    @Operation(summary = "工单多维高级条件分页检索")
    @PostMapping("/orders/search")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN', 'DISPATCHER')")
    public R<Page<DispatchOrderItemVO>> searchOrders(@RequestBody WorkOrderFilterQueryRequest request) {
        return R.success(analyticsService.queryOrdersWithFilter(request));
    }

    @Operation(summary = "工单多维组合检索结果流式导出标准 Excel")
    @PostMapping(value = {"/orders/export-excel", "/work-orders/export-excel"})
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN', 'DISPATCHER')")
    public void exportOrdersExcel(
            @RequestBody WorkOrderFilterQueryRequest request,
            HttpServletResponse response) throws IOException {
        List<WorkOrderExportVO> list = analyticsService.getOrdersForExport(request);

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = URLEncoder.encode("校园设施报修工单分析台账_" + System.currentTimeMillis(), StandardCharsets.UTF_8).replaceAll("\\+", "%20");
        response.setHeader("Content-disposition", "attachment;filename*=utf-8''" + fileName + ".xlsx");

        EasyExcel.write(response.getOutputStream(), WorkOrderExportVO.class)
                .sheet("工单明细报表")
                .doWrite(list);
    }

    @Operation(summary = "将超过 180 天历史已结单工单标记为冷数据归档")
    @PostMapping("/orders/archive")
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    public R<Integer> archiveOrders(@RequestParam(defaultValue = "180") Integer days) {
        return R.success(analyticsService.archiveHistoricalOrders(days));
    }
}
