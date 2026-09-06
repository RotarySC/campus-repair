package com.campus.repair.modules.workorder.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class DuplicateCheckRequest {

    @NotNull(message = "空间位置ID不能为空")
    private Long spaceId;

    @NotNull(message = "故障分类ID不能为空")
    private Long categoryId;
}
