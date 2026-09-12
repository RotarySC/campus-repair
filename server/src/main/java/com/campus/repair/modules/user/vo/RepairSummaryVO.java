package com.campus.repair.modules.user.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * 师生「我的报修概览」（个人中心统计区）。
 *
 * <p>分桶口径与「我的工单」页签一致：
 * 待派单 = PENDING_DISPATCH + MANUAL_DISPATCH；
 * 在办 = ASSIGNED + IN_PROGRESS + SUSPENDED + REWORK_PENDING；
 * 待验收 = PENDING_ACCEPTANCE；已结单 = COMPLETED；已作废 = CANCELLED。
 */
@Data
@Builder
public class RepairSummaryVO implements Serializable {

    private Integer totalCount;
    private Integer pendingDispatchCount;
    private Integer processingCount;
    private Integer pendingAcceptanceCount;
    private Integer completedCount;
    private Integer cancelledCount;

    /** 本人给出的评价星级均值；**无有效评价时为 null**，前端渲染 `—`（不得兜底成假好评） */
    private Double avgRating;
}
