package com.campus.repair.modules.workorder.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class WorkOrderArbitrateReworkRequest {

    /**
     * 仲裁决策:
     * RETRY_ORIGINAL_WORKER - 退回原师傅限期整改
     * REASSIGN_NEW_WORKER - 换派其他资深技师
     */
    @NotBlank(message = "仲裁决策不能为空")
    private String arbitrationDecision;

    /**
     * 若决策为换派新师傅，则此字段为必填的目标师傅用户ID
     */
    private Long targetWorkerId;

    @NotBlank(message = "仲裁督办说明不能为空")
    private String arbitrationNote;
}
