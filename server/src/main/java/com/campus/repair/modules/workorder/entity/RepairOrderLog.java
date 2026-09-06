package com.campus.repair.modules.workorder.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("repair_order_log")
public class RepairOrderLog implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    private String orderNo;

    private Long operatorId;

    private String operatorName;

    private String operatorRole; // STUDENT, WORKER, ADMIN, SYSTEM

    private String actionType; // SUBMIT, AUTO_DISPATCH, MANUAL_DISPATCH, ACCEPT, SUSPEND, RESUME, TRANSFER_APPLY, FINISH_UPLOAD, AUTO_ACCEPT, MANUAL_ACCEPT, REWORK_TRIGGER, REWORK_ASSIGN, CANCEL

    private String fromStatus;

    private String toStatus;

    private String reasonOrRemark;

    private String evidenceImages; // JSON array

    private LocalDateTime createdAt;
}
