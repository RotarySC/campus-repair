package com.campus.repair.modules.inventory.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.repair.modules.inventory.dto.MaterialConsumeDTO;
import com.campus.repair.modules.inventory.dto.SparePartCreateUpdateRequest;
import com.campus.repair.modules.inventory.dto.SparePartInboundRequest;
import com.campus.repair.modules.inventory.dto.SparePartQueryRequest;
import com.campus.repair.modules.inventory.entity.RepairMaterialConsume;
import com.campus.repair.modules.inventory.entity.SparePartInbound;
import com.campus.repair.modules.inventory.vo.InventorySummaryVO;
import com.campus.repair.modules.inventory.vo.PurchaseAdviceExportVO;
import com.campus.repair.modules.inventory.vo.SparePartVO;

import java.math.BigDecimal;
import java.util.List;

public interface InventoryService {

    /**
     * 维修完工原子扣减物料库存，记录消耗流水，返回直接材料费合计
     */
    BigDecimal deductMaterialForOrder(Long orderId, String orderNo, Long workerId, List<MaterialConsumeDTO> items);

    /**
     * 分页查询耗材备件台账
     */
    Page<SparePartVO> getSparePartsPage(SparePartQueryRequest request);

    /**
     * 新增或编辑物料档案
     */
    SparePartVO saveOrUpdateSparePart(SparePartCreateUpdateRequest request);

    /**
     * 软删除物料档案
     */
    void deleteSparePart(Long id);

    /**
     * 采购批量入库登记（在事务中记录不可篡改流水并原子增加物理库存）
     */
    SparePartInbound registerInbound(Long operatorId, String operatorName, SparePartInboundRequest request);

    /**
     * 查询物资库存概况统计（总数、低库存报警数等）
     */
    InventorySummaryVO getInventorySummary();

    /**
     * 获取低于安全库存阈值的采购建议列表
     */
    List<PurchaseAdviceExportVO> getPurchaseAdviceList(String categoryType);

    /**
     * 查询某工单消耗的物料流水
     */
    List<RepairMaterialConsume> getConsumptionsByOrderId(Long orderId);

    /**
     * 查询指定物料的采购入库历史明细
     */
    List<SparePartInbound> getInboundHistoryByPartId(Long partId);
}

