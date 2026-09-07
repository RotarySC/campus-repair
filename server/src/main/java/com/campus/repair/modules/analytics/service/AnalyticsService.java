package com.campus.repair.modules.analytics.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.repair.modules.analytics.dto.WorkOrderFilterQueryRequest;
import com.campus.repair.modules.analytics.vo.*;
import com.campus.repair.modules.workorder.vo.DispatchOrderItemVO;

import java.util.List;

public interface AnalyticsService {

    /**
     * 获取运营指挥大屏顶层 6 大核心 KPI
     */
    DashboardKpiVO getDashboardKpi();

    /**
     * 获取近 N 天每日提单与结单双面积趋势折线数据
     */
    TrendChartVO getTrendChart(int days);

    /**
     * 获取各空间网格维度报修频次热力排行
     */
    List<SpatialHeatVO> getSpatialDistribution(int limit);

    /**
     * 获取故障类别分布占比
     */
    List<CategoryRatioVO> getCategoryDistribution();

    /**
     * 获取维修师傅月度战绩排行榜
     */
    List<WorkerLeaderboardVO> getWorkerLeaderboard(int limit);

    /**
     * 多维组合高级条件检索工单列表（分页）
     */
    Page<DispatchOrderItemVO> queryOrdersWithFilter(WorkOrderFilterQueryRequest request);

    /**
     * 获取多维条件检索的全量数据，用于 EasyExcel 导出
     */
    List<WorkOrderExportVO> getOrdersForExport(WorkOrderFilterQueryRequest request);

    /**
     * 将超过指定天数（默认180天）的历史已结单工单标记为冷数据归档
     *
     * @param days 结单超期天数（默认 180）
     * @return 本次归档影响的工单记录数
     */
    int archiveHistoricalOrders(int days);
}
