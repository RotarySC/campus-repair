package com.campus.repair.modules.inventory.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.repair.common.exception.BusinessException;
import com.campus.repair.common.exception.ErrorCode;
import com.campus.repair.modules.inventory.dto.MaterialConsumeDTO;
import com.campus.repair.modules.inventory.dto.SparePartCreateUpdateRequest;
import com.campus.repair.modules.inventory.dto.SparePartInboundRequest;
import com.campus.repair.modules.inventory.dto.SparePartQueryRequest;
import com.campus.repair.modules.inventory.entity.RepairMaterialConsume;
import com.campus.repair.modules.inventory.entity.SparePart;
import com.campus.repair.modules.inventory.entity.SparePartInbound;
import com.campus.repair.modules.inventory.mapper.RepairMaterialConsumeMapper;
import com.campus.repair.modules.inventory.mapper.SparePartInboundMapper;
import com.campus.repair.modules.inventory.mapper.SparePartMapper;
import com.campus.repair.modules.inventory.vo.InventorySummaryVO;
import com.campus.repair.modules.inventory.vo.PurchaseAdviceExportVO;
import com.campus.repair.modules.inventory.vo.SparePartVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryServiceImpl implements InventoryService {

    private final SparePartMapper sparePartMapper;
    private final RepairMaterialConsumeMapper consumeMapper;
    private final SparePartInboundMapper inboundMapper;

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

            // 1. 获取物料元数据
            SparePart part = sparePartMapper.selectById(item.getPartId());
            if (part == null || part.getDeleted() != 0 || part.getStatus() != 1) {
                throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(),
                        String.format("物料 ID [%d] 不存在或已停用下架！", item.getPartId()));
            }

            // 2. 行级条件原子扣减：UPDATE spare_part SET stock_quantity = stock_quantity - ? WHERE id = ? AND stock_quantity >= ?
            int affected = sparePartMapper.deductStockAtomic(item.getPartId(), item.getConsumeQuantity());
            if (affected == 0) {
                int currentStock = part.getStockQuantity() != null ? part.getStockQuantity() : 0;
                log.warn("[Inventory] 物料 [{}] 物理库存不足! 当前库存: {}, 申请扣减: {}", part.getPartName(), currentStock, item.getConsumeQuantity());
                throw new BusinessException(ErrorCode.INVENTORY_OUT_OF_STOCK.getCode(),
                        String.format("物料 [%s] 实际物理库存不足（当前仅剩 %d 件，申请消耗 %d 件），无法核销出库！",
                                part.getPartName(), currentStock, item.getConsumeQuantity()));
            }

            // 3. 计算指导价格直接成本
            BigDecimal unitPrice = part.getGuidePrice() != null ? part.getGuidePrice() : BigDecimal.ZERO;
            BigDecimal itemTotalCost = unitPrice.multiply(BigDecimal.valueOf(item.getConsumeQuantity()));
            totalCost = totalCost.add(itemTotalCost);

            // 4. 记录维修现场耗材核销流水明细
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
        // 不限制 status = 1，允许查看全部状态的物料，支持后台维护

        if (StringUtils.hasText(request.getCategoryType()) && !"ALL".equalsIgnoreCase(request.getCategoryType().trim())) {
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
    @Transactional(rollbackFor = Exception.class)
    public SparePartVO saveOrUpdateSparePart(SparePartCreateUpdateRequest request) {
        // 校验编码唯一性
        LambdaQueryWrapper<SparePart> checkWrapper = new LambdaQueryWrapper<SparePart>()
                .eq(SparePart::getPartCode, request.getPartCode().trim());
        if (request.getId() != null) {
            checkWrapper.ne(SparePart::getId, request.getId());
        }
        if (sparePartMapper.selectCount(checkWrapper) > 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(), "物料编码 [" + request.getPartCode() + "] 已存在，请勿重复使用！");
        }

        SparePart entity;
        if (request.getId() != null) {
            entity = sparePartMapper.selectById(request.getId());
            if (entity == null || entity.getDeleted() != 0) {
                throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(), "指定的备件档案不存在！");
            }
        } else {
            entity = new SparePart();
            entity.setStockQuantity(0); // 初始库存默认为 0，必须通过入库单规范累加
            entity.setCreatedAt(LocalDateTime.now());
        }

        entity.setPartCode(request.getPartCode().trim());
        entity.setPartName(request.getPartName().trim());
        entity.setCategoryType(request.getCategoryType().trim());
        entity.setSpecification(request.getSpecification() != null ? request.getSpecification().trim() : "");
        entity.setUnit(request.getUnit().trim());
        entity.setGuidePrice(request.getGuidePrice());
        entity.setSafetyStockThreshold(request.getSafetyStockThreshold());
        entity.setStatus(request.getStatus() != null ? request.getStatus() : 1);
        entity.setUpdatedAt(LocalDateTime.now());

        if (entity.getId() == null) {
            sparePartMapper.insert(entity);
        } else {
            sparePartMapper.updateById(entity);
        }

        SparePartVO vo = new SparePartVO();
        BeanUtils.copyProperties(entity, vo);
        vo.setIsLowStock(entity.getStockQuantity() <= entity.getSafetyStockThreshold());
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteSparePart(Long id) {
        SparePart part = sparePartMapper.selectById(id);
        if (part == null || part.getDeleted() != 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(), "物料档案不存在！");
        }
        if (part.getStockQuantity() != null && part.getStockQuantity() > 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(),
                    String.format("物料 [%s] 尚有物理在库库存 %d 件，禁止直接删除！可先将其设为停用下架。", part.getPartName(), part.getStockQuantity()));
        }
        sparePartMapper.deleteById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SparePartInbound registerInbound(Long operatorId, String operatorName, SparePartInboundRequest request) {
        SparePart part = sparePartMapper.selectById(request.getPartId());
        if (part == null || part.getDeleted() != 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID.getCode(), "入库目标物料不存在！");
        }

        LocalDate batchDate = request.getBatchDate() != null ? request.getBatchDate() : LocalDate.now();
        BigDecimal totalAmount = request.getUnitPrice().multiply(BigDecimal.valueOf(request.getQuantity()));

        // 生成入库流水单号: IN + yyyyMMdd + 6位序列/随机数
        String dateStr = DateTimeFormatter.ofPattern("yyyyMMdd").format(LocalDate.now());
        String inboundNo = String.format("IN%s%06d", dateStr, ThreadLocalRandom.current().nextInt(100000, 999999));

        SparePartInbound inbound = new SparePartInbound();
        inbound.setInboundNo(inboundNo);
        inbound.setPartId(part.getId());
        inbound.setPartName(part.getPartName());
        inbound.setBatchDate(batchDate);
        inbound.setQuantity(request.getQuantity());
        inbound.setUnitPrice(request.getUnitPrice());
        inbound.setTotalAmount(totalAmount);
        inbound.setOperatorId(operatorId);
        inbound.setOperatorName(operatorName != null ? operatorName : "采购经办人");
        inbound.setRemark(request.getRemark());
        inbound.setCreatedAt(LocalDateTime.now());
        inboundMapper.insert(inbound);

        // 原子累加物料在库物理库存
        sparePartMapper.addStockAtomic(part.getId(), request.getQuantity());

        log.info("[Inventory] 采购入库登记成功: 单号={}, 物料={}, 入库量={}, 采购金额=¥{}",
                inboundNo, part.getPartName(), request.getQuantity(), totalAmount);

        return inbound;
    }

    @Override
    public InventorySummaryVO getInventorySummary() {
        long total = sparePartMapper.selectCount(new LambdaQueryWrapper<SparePart>().eq(SparePart::getDeleted, 0));
        long lowStock = sparePartMapper.selectCount(new LambdaQueryWrapper<SparePart>()
                .eq(SparePart::getDeleted, 0)
                .eq(SparePart::getStatus, 1)
                .apply("stock_quantity <= safety_stock_threshold"));
        long disabled = sparePartMapper.selectCount(new LambdaQueryWrapper<SparePart>()
                .eq(SparePart::getDeleted, 0)
                .eq(SparePart::getStatus, 0));
        long normal = total - lowStock - disabled;
        if (normal < 0) normal = 0;

        return InventorySummaryVO.builder()
                .totalPartCount(total)
                .lowStockCount(lowStock)
                .normalCount(normal)
                .disabledCount(disabled)
                .build();
    }

    @Override
    public List<PurchaseAdviceExportVO> getPurchaseAdviceList(String categoryType) {
        LambdaQueryWrapper<SparePart> query = new LambdaQueryWrapper<SparePart>()
                .eq(SparePart::getDeleted, 0)
                .eq(SparePart::getStatus, 1)
                .apply("stock_quantity <= safety_stock_threshold");

        if (StringUtils.hasText(categoryType) && !"ALL".equalsIgnoreCase(categoryType.trim())) {
            query.eq(SparePart::getCategoryType, categoryType.trim());
        }

        query.orderByAsc(SparePart::getCategoryType)
             .orderByAsc(SparePart::getStockQuantity);

        List<SparePart> list = sparePartMapper.selectList(query);
        List<PurchaseAdviceExportVO> result = new ArrayList<>();

        for (SparePart p : list) {
            result.add(PurchaseAdviceExportVO.fromSparePart(p));
        }

        return result;
    }

    @Override
    public List<RepairMaterialConsume> getConsumptionsByOrderId(Long orderId) {
        LambdaQueryWrapper<RepairMaterialConsume> query = new LambdaQueryWrapper<>();
        query.eq(RepairMaterialConsume::getOrderId, orderId)
             .orderByAsc(RepairMaterialConsume::getId);
        return consumeMapper.selectList(query);
    }

    @Override
    public List<SparePartInbound> getInboundHistoryByPartId(Long partId) {
        LambdaQueryWrapper<SparePartInbound> query = new LambdaQueryWrapper<>();
        query.eq(SparePartInbound::getPartId, partId)
             .orderByDesc(SparePartInbound::getCreatedAt);
        return inboundMapper.selectList(query);
    }
}

