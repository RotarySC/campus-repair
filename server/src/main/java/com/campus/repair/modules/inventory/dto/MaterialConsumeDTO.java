package com.campus.repair.modules.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MaterialConsumeDTO {

    @NotNull(message = "耗材物料ID不能为空")
    private Long partId;

    @NotNull(message = "消耗数量不能为空")
    @Min(value = 1, message = "消耗数量必须大于0")
    private Integer consumeQuantity;
}
