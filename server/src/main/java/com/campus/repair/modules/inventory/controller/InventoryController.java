package com.campus.repair.modules.inventory.controller;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.repair.common.result.R;
import com.campus.repair.framework.security.LoginUserPrincipal;
import com.campus.repair.modules.inventory.dto.SparePartCreateUpdateRequest;
import com.campus.repair.modules.inventory.dto.SparePartInboundRequest;
import com.campus.repair.modules.inventory.dto.SparePartQueryRequest;
import com.campus.repair.modules.inventory.entity.RepairMaterialConsume;
import com.campus.repair.modules.inventory.entity.SparePartInbound;
import com.campus.repair.modules.inventory.service.InventoryService;
import com.campus.repair.modules.inventory.vo.InventorySummaryVO;
import com.campus.repair.modules.inventory.vo.PurchaseAdviceExportVO;
import com.campus.repair.modules.inventory.vo.SparePartVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Tag(name = "耗材备件轻量进销存", description = "耗材台账查询、CRUD、核销流水、采购入库与补货建议导出")
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

    @Operation(summary = "新增或修改耗材备件档案")
    @PostMapping("/parts")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN', 'DISPATCHER')")
    public R<SparePartVO> saveOrUpdatePart(@Valid @RequestBody SparePartCreateUpdateRequest request) {
        return R.success(inventoryService.saveOrUpdateSparePart(request));
    }

    @Operation(summary = "删除耗材备件档案")
    @DeleteMapping("/parts/{id}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN', 'DISPATCHER')")
    public R<Void> deletePart(@PathVariable Long id) {
        inventoryService.deleteSparePart(id);
        return R.success();
    }

    @Operation(summary = "登记采购批量入库")
    @PostMapping("/inbound")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN', 'DISPATCHER')")
    public R<SparePartInbound> registerInbound(
            @AuthenticationPrincipal LoginUserPrincipal user,
            @Valid @RequestBody SparePartInboundRequest request) {
        return R.success(inventoryService.registerInbound(user.getUserId(), user.getUsername(), request));
    }

    @Operation(summary = "查询耗材库存概况统计指标（总数、低库存警戒数等）")
    @GetMapping("/summary")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN', 'DISPATCHER')")
    public R<InventorySummaryVO> getSummary() {
        return R.success(inventoryService.getInventorySummary());
    }

    @Operation(summary = "流式导出缺料采购补货建议清单 Excel")
    @GetMapping("/export-purchase-advice")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN', 'DISPATCHER')")
    public void exportPurchaseAdvice(
            @RequestParam(required = false) String categoryType,
            HttpServletResponse response) throws IOException {
        List<PurchaseAdviceExportVO> list = inventoryService.getPurchaseAdviceList(categoryType);

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        String fileName = URLEncoder.encode("耗材采购补货建议清单_" + System.currentTimeMillis(), StandardCharsets.UTF_8).replaceAll("\\+", "%20");
        response.setHeader("Content-disposition", "attachment;filename*=utf-8''" + fileName + ".xlsx");

        EasyExcel.write(response.getOutputStream(), PurchaseAdviceExportVO.class)
                .sheet("低库存采购补货建议")
                .doWrite(list);
    }

    @Operation(summary = "查询指定工单关联的耗材领用核销明细")
    @GetMapping("/consumptions/{orderId}")
    public R<List<RepairMaterialConsume>> getOrderConsumptions(@PathVariable Long orderId) {
        return R.success(inventoryService.getConsumptionsByOrderId(orderId));
    }

    @Operation(summary = "查询指定物料的历史采购入库流水明细")
    @GetMapping("/inbound/history/{partId}")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN', 'DISPATCHER')")
    public R<List<SparePartInbound>> getInboundHistory(@PathVariable Long partId) {
        return R.success(inventoryService.getInboundHistoryByPartId(partId));
    }
}

