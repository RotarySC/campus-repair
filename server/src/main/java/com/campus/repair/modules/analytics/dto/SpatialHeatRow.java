package com.campus.repair.modules.analytics.dto;

/**
 * 空间热力按 {@code space_full_path} 原值分组的单行结果。
 *
 * <p>这里刻意返回<b>原始路径</b>而非已截断的标签：截断成「校区/分区」的字符串处理
 * 放在上层完成（见 {@code AnalyticsServiceImpl#getSpatialDistribution}），
 * 以规避 H2 对 {@code LOCATE} 表达式求值不一致的问题。
 */
public class SpatialHeatRow {

    /** 工单上固化的空间全路径原值 */
    private String spacePath;

    /** 该路径下工单数 */
    private Long orderCount;

    public String getSpacePath() {
        return spacePath;
    }

    public void setSpacePath(String spacePath) {
        this.spacePath = spacePath;
    }

    public Long getOrderCount() {
        return orderCount;
    }

    public void setOrderCount(Long orderCount) {
        this.orderCount = orderCount;
    }
}
