package com.campus.repair.modules.workorder.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("repair_order")
public class RepairOrder implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String orderNo;

    private Long studentUserId;

    private String applicantName;

    private String applicantPhone;

    private Long spaceId;

    private String spaceFullPath;

    private Long categoryId;

    private String categoryFullName;

    private String workerSkillCode;

    private String description;

    private String faultImages; // JSON 字符串数组

    private Integer urgencyLevel; // 1-普通日常, 2-加急紧急

    private String appointmentPeriod;

    private String status; // PENDING_DISPATCH, MANUAL_DISPATCH, ASSIGNED, IN_PROGRESS, SUSPENDED, PENDING_ACCEPTANCE, REWORK_PENDING, COMPLETED, CANCELLED

    private Long workerId;

    private String workerName;

    private String workerPhone;

    private String dispatchType; // AUTO, MANUAL

    private LocalDateTime dispatchedTime;

    private LocalDateTime acceptedTime;

    private LocalDateTime finishTime;

    private LocalDateTime closedTime;

    private LocalDateTime slaDeadline;

    private Integer isSlaTimeout; // 0-否, 1-是

    private Integer suspendCount;

    private Integer totalSuspendMinutes;

    private Integer reworkCount;

    private Integer isTransferApply; // 0-否, 1-是 (师傅申请转派标记)

    private BigDecimal materialCost;

    private String cancelReason;

    private Integer isArchived; // 0-活跃, 1-归档

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
