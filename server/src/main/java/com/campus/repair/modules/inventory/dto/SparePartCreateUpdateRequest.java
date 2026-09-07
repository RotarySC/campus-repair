package com.campus.repair.modules.inventory.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class SparePartCreateUpdateRequest {

    private Long id;

    @NotBlank(message = "物料编码不能为空")
    private String partCode;

    @NotBlank(message = "物料名称不能为空")
    private String partName;

    @NotBlank(message = "耗材类别不能为空")
    private String categoryType;

    private String specification;

    @NotBlank(message = "计量单位不能为空")
    private String unit;

    @NotNull(message = "指导单价不能为空")
    @DecimalMin(value = "0.00", message = "指导单价不能小于0")
    private BigDecimal guidePrice;

    @NotNull(message = "安全库存阈值不能为空")
    @Min(value = 0, message = "安全库存阈值不能小于0")
    private Integer safetyStockThreshold;

    @NotNull(message = "状态不能为空")
    private Integer status; // 1-正常在架, 0-停用下架
}
