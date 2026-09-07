package com.campus.repair.modules.inventory.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.repair.modules.inventory.dto.MaterialConsumeDTO;
import com.campus.repair.modules.inventory.dto.SparePartQueryRequest;
import com.campus.repair.modules.inventory.entity.RepairMaterialConsume;
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
     * 查询某工单消耗的物料流水
     */
    List<RepairMaterialConsume> getConsumptionsByOrderId(Long orderId);
}
