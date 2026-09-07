package com.campus.repair.modules.inventory.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class SparePartInboundRequest {

    @NotNull(message = "入库物料ID不能为空")
    private Long partId;

    @NotNull(message = "入库数量不能为空")
    @Min(value = 1, message = "入库数量必须大于0")
    private Integer quantity;

    @NotNull(message = "实际采购单价不能为空")
    @DecimalMin(value = "0.00", message = "实际采购单价不能为负数")
    private BigDecimal unitPrice;

    private LocalDate batchDate;

    private String remark;
}
