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
public class WorkerLeaderboardVO {

    private Long userId;

    private String realName;

    private String skillCode;

    private Integer completedCount;

    private BigDecimal avgHours;

    private BigDecimal ratingScore;
}
