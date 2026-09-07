package com.campus.repair.modules.workorder.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

@Data
public class WorkOrderSuspendRequest implements Serializable {

    /**
     * 挂起原因类型:
     * MATERIAL_SHORTAGE: 现场缺料
     * OUTSOURCING_BLOCK: 外协阻滞
     * USER_TIME_CHANGE: 报修人改期
     * OTHER: 其他现场特殊原因
     */
    @NotBlank(message = "挂起原因类型不能为空")
    private String suspendReasonType;

    /**
     * 详细原因说明
     */
    @NotBlank(message = "挂起原因详细说明不能为空")
    private String reasonDetail;

    /**
     * 预计恢复施工时间 (如: 2026-09-08 10:00:00)
     */
    private String estimatedResumeTime;
}
