package com.campus.repair.modules.inventory.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class SparePartVO {

    private Long id;

    private String partCode;

    private String partName;

    private String categoryType;

    private String specification;

    private String unit;

    private BigDecimal guidePrice;

    private Integer stockQuantity;

    private Integer safetyStockThreshold;

    private Integer status;

    private Boolean isLowStock;

    private LocalDateTime createdAt;
}
