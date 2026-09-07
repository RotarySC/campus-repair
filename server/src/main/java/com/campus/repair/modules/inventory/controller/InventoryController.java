package com.campus.repair.modules.inventory.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.repair.common.result.R;
import com.campus.repair.modules.inventory.dto.SparePartQueryRequest;
import com.campus.repair.modules.inventory.entity.RepairMaterialConsume;
import com.campus.repair.modules.inventory.service.InventoryService;
import com.campus.repair.modules.inventory.vo.SparePartVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "耗材备件轻量进销存", description = "耗材台账查询、核销流水与出入库管理")
@RestController
@RequestMapping("/api/v1/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @Operation(summary = "分页查询耗材备件物料清单")
    @GetMapping("/parts")
    public R<Page<SparePartVO>> getSpareParts(SparePartQueryRequest request) {
        return R.success(inventoryService.getSparePartsPage(request));
    }

    @Operation(summary = "查询指定工单关联的耗材领用核销明细")
    @GetMapping("/consumptions/{orderId}")
    public R<List<RepairMaterialConsume>> getOrderConsumptions(@PathVariable Long orderId) {
        return R.success(inventoryService.getConsumptionsByOrderId(orderId));
    }
}
