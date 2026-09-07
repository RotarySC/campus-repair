package com.campus.repair.modules.inventory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.repair.common.exception.BusinessException;
import com.campus.repair.common.exception.ErrorCode;
import com.campus.repair.modules.inventory.dto.MaterialConsumeDTO;
import com.campus.repair.modules.inventory.dto.SparePartQueryRequest;
import com.campus.repair.modules.inventory.entity.RepairMaterialConsume;
import com.campus.repair.modules.inventory.entity.SparePart;
import com.campus.repair.modules.inventory.mapper.RepairMaterialConsumeMapper;
import com.campus.repair.modules.inventory.mapper.SparePartMapper;
import com.campus.repair.modules.inventory.vo.SparePartVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {

    private final SparePartMapper sparePartMapper;
    private final RepairMaterialConsumeMapper consumeMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public BigDecimal deductMaterialForOrder(Long orderId, String orderNo, Long workerId, List<MaterialConsumeDTO> items) {
        if (items == null || items.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal totalCost = BigDecimal.ZERO;

        for (MaterialConsumeDTO item : items) {
            if (item.getConsumeQuantity() == null || item.getConsumeQuantity() <= 0) {
                continue;
            }

            // 1. 行级条件原子扣减：UPDATE spare_part SET stock_quantity = stock_quantity - ? WHERE id = ? AND stock_quantity >= ?
            int affected = sparePartMapper.deductStockAtomic(item.getPartId(), item.getConsumeQuantity());
            if (affected == 0) {
                SparePart part = sparePartMapper.selectById(item.getPartId());
                String partName = part != null ? part.getPartName() : "ID:" + item.getPartId();
                int currentStock = part != null ? part.getStockQuantity() : 0;
                log.warn("[Inventory] 物料 [{}] 物理库存不足! 当前库存: {}, 申请扣减: {}", partName, currentStock, item.getConsumeQuantity());
                throw new BusinessException(ErrorCode.INVENTORY_OUT_OF_STOCK.getCode(),
                        String.format("物料 [%s] 实际物理库存不足（当前仅剩 %d 件，申请消耗 %d 件），无法核销出库！",
                                partName, currentStock, item.getConsumeQuantity()));
            }

            // 2. 获取指导价格计算直接成本
            SparePart part = sparePartMapper.selectById(item.getPartId());
            BigDecimal unitPrice = part.getGuidePrice() != null ? part.getGuidePrice() : BigDecimal.ZERO;
            BigDecimal itemTotalCost = unitPrice.multiply(BigDecimal.valueOf(item.getConsumeQuantity()));
            totalCost = totalCost.add(itemTotalCost);

            // 3. 记录维修现场耗材核销流水明细
            RepairMaterialConsume consume = new RepairMaterialConsume();
            consume.setOrderId(orderId);
            consume.setOrderNo(orderNo);
            consume.setWorkerId(workerId);
            consume.setPartId(part.getId());
            consume.setPartName(part.getPartName());
            consume.setConsumeQuantity(item.getConsumeQuantity());
            consume.setUnitPrice(unitPrice);
            consume.setTotalCost(itemTotalCost);
            consumeMapper.insert(consume);
        }

        return totalCost;
    }

    @Override
    public Page<SparePartVO> getSparePartsPage(SparePartQueryRequest request) {
        int pageNo = request.getPage() != null && request.getPage() > 0 ? request.getPage() : 1;
        int pageSize = request.getSize() != null && request.getSize() > 0 ? request.getSize() : 20;

        LambdaQueryWrapper<SparePart> query = new LambdaQueryWrapper<>();
        query.eq(SparePart::getStatus, 1); // 默认查询在架物料

        if (StringUtils.hasText(request.getCategoryType())) {
            query.eq(SparePart::getCategoryType, request.getCategoryType().trim());
        }

        if (StringUtils.hasText(request.getKeyword())) {
            String kw = request.getKeyword().trim();
            query.and(w -> w.like(SparePart::getPartName, kw)
                    .or().like(SparePart::getPartCode, kw)
                    .or().like(SparePart::getSpecification, kw));
        }

        if (Boolean.TRUE.equals(request.getLowStockOnly())) {
            query.apply("stock_quantity <= safety_stock_threshold");
        }

        query.orderByAsc(SparePart::getCategoryType)
             .orderByAsc(SparePart::getId);

        Page<SparePart> page = sparePartMapper.selectPage(new Page<>(pageNo, pageSize), query);

        List<SparePartVO> voList = new ArrayList<>();
        for (SparePart p : page.getRecords()) {
            SparePartVO vo = new SparePartVO();
            BeanUtils.copyProperties(p, vo);
            vo.setIsLowStock(p.getStockQuantity() <= p.getSafetyStockThreshold());
            voList.add(vo);
        }

        Page<SparePartVO> resultPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        resultPage.setRecords(voList);
        return resultPage;
    }

    @Override
    public List<RepairMaterialConsume> getConsumptionsByOrderId(Long orderId) {
        LambdaQueryWrapper<RepairMaterialConsume> query = new LambdaQueryWrapper<>();
        query.eq(RepairMaterialConsume::getOrderId, orderId)
             .orderByAsc(RepairMaterialConsume::getId);
        return consumeMapper.selectList(query);
    }
}
