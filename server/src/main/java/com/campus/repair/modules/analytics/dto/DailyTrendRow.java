package com.campus.repair.modules.analytics.dto;

import java.time.LocalDate;

/**
 * 趋势图按日聚合的单行结果（数据库侧 GROUP BY 日期）。
 *
 * <p>日期用 {@code CAST(ts AS DATE)} 取回后映射为 {@link LocalDate}，
 * 再在上层按 {@code yyyy-MM-dd} 格式化——刻意不用 {@code DATE_FORMAT}，
 * 因为 H2（测试库）不提供该函数。
 */
public class DailyTrendRow {

    /** 统计日期 */
    private LocalDate statDate;

    /** 当日提单量 */
    private Long submitCount;

    /** 当日结单量 */
    private Long completedCount;

    public LocalDate getStatDate() {
        return statDate;
    }

    public void setStatDate(LocalDate statDate) {
        this.statDate = statDate;
    }

    public Long getSubmitCount() {
        return submitCount;
    }

    public void setSubmitCount(Long submitCount) {
        this.submitCount = submitCount;
    }

    public Long getCompletedCount() {
        return completedCount;
    }

    public void setCompletedCount(Long completedCount) {
        this.completedCount = completedCount;
    }
}
