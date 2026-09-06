package com.campus.repair.modules.workorder.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

@Data
public class WorkOrderManualDispatchRequest implements Serializable {

    @NotNull(message = "必须指定指派维修师傅ID")
    private Long workerId;

    /**
     * 调度指派说明/备注
     */
    private String dispatchRemark;
}
