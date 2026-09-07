package com.campus.repair.modules.analytics.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrendChartVO {

    /**
     * 日期序列 (yyyy-MM-dd)
     */
    private List<String> dates;

    /**
     * 每日提交量
     */
    private List<Long> submitCounts;

    /**
     * 每日完工结单量
     */
    private List<Long> completedCounts;
}
