package com.campus.repair.modules.inventory.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventorySummaryVO {

    private long totalPartCount;

    private long lowStockCount;

    private long normalCount;

    private long disabledCount;
}
