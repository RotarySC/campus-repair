package com.campus.repair.modules.workorder.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class WorkOrderEvaluationVO {

    private Long orderId;

    private String orderNo;

    // 报修现场信息
    private String description;

    private List<String> faultImages;

    // 师傅现场完工信息
    private String repairSummary;

    private List<String> repairImages;

    private LocalDateTime finishReportedAt;

    private BigDecimal materialCost;

    // 评价与仲裁信息
    private Integer ratingStar;

    private List<String> ratingTags;

    private String evaluationComment;

    private LocalDateTime evaluatedAt;

    private Integer isAutoConfirmed;

    private Integer reworkFlag;

    private String arbitrationNote;
}
