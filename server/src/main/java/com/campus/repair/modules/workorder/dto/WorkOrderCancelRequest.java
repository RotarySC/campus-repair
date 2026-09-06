package com.campus.repair.modules.workorder.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

@Data
public class WorkOrderCancelRequest implements Serializable {

    @NotBlank(message = "作废原因不能为空")
    private String cancelReason;
}
