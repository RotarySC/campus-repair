package com.campus.repair.modules.analytics.dto;

/**
 * 故障大类占比按 {@code category_full_name} 原值分组的单行结果。
 *
 * <p>与 {@link SpatialHeatRow} 同理，返回原始类别路径，首段截断由上层完成。
 */
public class CategoryRatioRow {

    /** 工单上固化的故障类别全路径原值 */
    private String categoryPath;

    /** 该类别下工单数 */
    private Long orderCount;

    public String getCategoryPath() {
        return categoryPath;
    }

    public void setCategoryPath(String categoryPath) {
        this.categoryPath = categoryPath;
    }

    public Long getOrderCount() {
        return orderCount;
    }

    public void setOrderCount(Long orderCount) {
        this.orderCount = orderCount;
    }
}
