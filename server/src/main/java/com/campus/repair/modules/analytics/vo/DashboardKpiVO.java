package com.campus.repair.modules.analytics.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardKpiVO {

    /**
     * 累计历史工单总量
     */
    private Long totalOrderCount;

    /**
     * 今日新增提单量
     */
    private Long todayNewOrderCount;

    /**
     * 当前在修在办工单量 (ASSIGNED, IN_PROGRESS, SUSPENDED)
     */
    private Long activeProcessingCount;

    /**
     * 完工闭环率 (百分比 0-100)
     */
    private BigDecimal completionRatePercent;

    /**
     * 平均响应解决时效 (小时)
     */
    private BigDecimal averageResponseHours;

    /**
     * 综合满意度均分 (1.00 - 5.00)
     */
    private BigDecimal satisfactionScore;
}
