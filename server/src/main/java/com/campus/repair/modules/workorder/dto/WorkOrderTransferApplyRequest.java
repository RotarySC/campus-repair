package com.campus.repair.modules.workorder.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

@Data
public class WorkOrderTransferApplyRequest implements Serializable {

    @NotBlank(message = "转派原因说明不能为空")
    private String transferReason;

    /**
     * 现场推荐合适工种编码 (ELECTRICIAN, PLUMBER, HVAC, LOCKSMITH, NETWORK 等)
     */
    @NotBlank(message = "请推荐建议转派的专业工种")
    private String recommendSkillCode;
}
