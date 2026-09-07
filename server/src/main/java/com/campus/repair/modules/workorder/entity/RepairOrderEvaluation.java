package com.campus.repair.modules.workorder.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("repair_order_evaluation")
public class RepairOrderEvaluation implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    private String orderNo;

    private String repairSummary;

    private String repairImages; // JSON 数组或逗号分隔

    private LocalDateTime finishReportedAt;

    private Long evaluatorId;

    private Integer ratingStar;

    private String ratingTags; // JSON 数组

    private String evaluationComment;

    private LocalDateTime evaluatedAt;

    private Integer isAutoConfirmed; // 0-否, 1-是

    private Integer reworkFlag; // 0-否, 1-是

    private Long arbitrationAdminId;

    private String arbitrationNote;
}
