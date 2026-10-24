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

    /**
     * 已结单工单数（{@code status = COMPLETED} 且未逻辑删除），即返工率的分母。
     *
     * <p>单独返回它是为了让前端能区分两种都返回 {@code reworkRatePercent = 0.00} 的情况：
     * 「至今还没有任何工单结单」与「有结单、且真的零返工」——只有分母为 0 时才渲染「—」。
     */
    private Long completedOrderCount;

    /**
     * 返工率（百分比 0-100，{@code HALF_UP} 保留 2 位小数）。
     *
     * <p><b>口径</b>：已结单且 {@code rework_count > 0} 的工单数 / 已结单工单数 × 100。
     * 强调三点，避免后来人按直觉改错：
     * <ol>
     *   <li><b>按单去重，不按次数</b>：一张工单返工多次（{@code rework_count} 累加）仍只算 1 单；
     *       若按次数求与会突破 100%，也无法与「完工闭环率」互相解释。
     *       {@code rework_count} 保留为「返工次数」明细，二者不是同一个指标。</li>
     *   <li><b>只看终态</b>：分母复用「完工闭环率」的分子 {@code completedOrders}；在办返工
     *       （{@code REWORK_PENDING} 等未结单状态）既不计入分子也不计入分母——返工率衡量的是
     *       「已交付结果中有多少需要返工」，在办量由 {@code activeProcessingCount} 负责。</li>
     *   <li><b>无已结单时为 0.00</b>（而不是 100 或 null），由前端依据 {@link #completedOrderCount}
     *       渲染为「—」，与满意度「无有效评价」的处理方式一致。</li>
     * </ol>
     */
    private BigDecimal reworkRatePercent;
}
