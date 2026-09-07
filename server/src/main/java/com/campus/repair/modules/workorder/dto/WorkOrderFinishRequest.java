package com.campus.repair.modules.workorder.dto;

import com.campus.repair.modules.inventory.dto.MaterialConsumeDTO;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class WorkOrderFinishRequest {

    @NotBlank(message = "维修处理总结描述不能为空")
    private String repairSummary;

    @NotEmpty(message = "请至少上传1张完工现场照片")
    private List<String> repairImages;

    /**
     * 本次维修现场消耗的耗材清单（可为空，表示纯人工免耗材维修）
     */
    private List<MaterialConsumeDTO> materialConsumptions;
}
