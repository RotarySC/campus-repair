package com.campus.repair.modules.inventory.dto;

import lombok.Data;

@Data
public class SparePartQueryRequest {

    private String keyword;

    private String categoryType;

    private Boolean lowStockOnly; // 仅看库存不足安全阈值的物料

    private Integer page = 1;

    private Integer size = 20;
}
