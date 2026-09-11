package com.campus.repair.modules.analytics.dto;

/**
 * 大屏顶层 KPI 的单行条件聚合结果（一次查询取回全部计数，避免多次往返）。
 */
public class DashboardAggregateRow {

    /** 历史总工单量 */
    private Long totalOrders;

    /** 今日新增工单量 */
    private Long todayOrders;

    /** 在修在办工单量（ASSIGNED / IN_PROGRESS / SUSPENDED） */
    private Long activeOrders;

    /** 完工闭环量（COMPLETED） */
    private Long completedOrders;

    /** 已闭环且闭环耗时有效的工单数 */
    private Long timedOrders;

    /** 已闭环工单的闭环总分钟数（每单下限 1 分钟，与原口径一致） */
    private Long totalMinutes;

    /** 有效评价笔数（rating_star 非空且大于 0） */
    private Long evalCount;

    /** 有效评价星级总和 */
    private Long evalStarSum;

    public Long getTotalOrders() {
        return totalOrders;
    }

    public void setTotalOrders(Long totalOrders) {
        this.totalOrders = totalOrders;
    }

    public Long getTodayOrders() {
        return todayOrders;
    }

    public void setTodayOrders(Long todayOrders) {
        this.todayOrders = todayOrders;
    }

    public Long getActiveOrders() {
        return activeOrders;
    }

    public void setActiveOrders(Long activeOrders) {
        this.activeOrders = activeOrders;
    }

    public Long getCompletedOrders() {
        return completedOrders;
    }

    public void setCompletedOrders(Long completedOrders) {
        this.completedOrders = completedOrders;
    }

    public Long getTimedOrders() {
        return timedOrders;
    }

    public void setTimedOrders(Long timedOrders) {
        this.timedOrders = timedOrders;
    }

    public Long getTotalMinutes() {
        return totalMinutes;
    }

    public void setTotalMinutes(Long totalMinutes) {
        this.totalMinutes = totalMinutes;
    }

    public Long getEvalCount() {
        return evalCount;
    }

    public void setEvalCount(Long evalCount) {
        this.evalCount = evalCount;
    }

    public Long getEvalStarSum() {
        return evalStarSum;
    }

    public void setEvalStarSum(Long evalStarSum) {
        this.evalStarSum = evalStarSum;
    }
}
