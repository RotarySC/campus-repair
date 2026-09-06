package com.campus.repair.modules.workorder.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class DispatchOrderItemVO implements Serializable {

    private Long id;
    private String orderNo;
    private Long studentUserId;
    private String applicantName;
    private String applicantPhone;
    private Long spaceId;
    private Long campusId;
    private String campusName;
    private String spaceFullPath;
    private Long categoryId;
    private String categoryFullName;
    private String workerSkillCode;
    private String description;
    private String faultImages;
    private Integer urgencyLevel;
    private String appointmentPeriod;
    private String status;
    private Long workerId;
    private String workerName;
    private String workerPhone;
    private String dispatchType;
    private LocalDateTime dispatchedTime;
    private LocalDateTime slaDeadline;
    private Integer isSlaTimeout;
    private Integer reworkCount;
    private Integer suspendCount;
    private BigDecimal materialCost;
    private String cancelReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /**
     * 待办原因/流转说明提示 (如自动派单失败原因、申请转派理由、差评返工原因)
     */
    private String dispatchReason;
}
