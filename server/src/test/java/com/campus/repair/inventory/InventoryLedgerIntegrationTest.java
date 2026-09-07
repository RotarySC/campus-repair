package com.campus.repair.inventory;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.repair.CampusRepairApplication;
import com.campus.repair.modules.inventory.dto.SparePartCreateUpdateRequest;
import com.campus.repair.modules.inventory.dto.SparePartInboundRequest;
import com.campus.repair.modules.inventory.dto.SparePartQueryRequest;
import com.campus.repair.modules.inventory.entity.SparePart;
import com.campus.repair.modules.inventory.entity.SparePartInbound;
import com.campus.repair.modules.inventory.mapper.SparePartMapper;
import com.campus.repair.modules.inventory.service.InventoryService;
import com.campus.repair.modules.inventory.vo.InventorySummaryVO;
import com.campus.repair.modules.inventory.vo.PurchaseAdviceExportVO;
import com.campus.repair.modules.inventory.vo.SparePartVO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@SpringBootTest(classes = CampusRepairApplication.class)
@ActiveProfiles("test")
public class InventoryLedgerIntegrationTest {

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private SparePartMapper sparePartMapper;

    @Test
    @DisplayName("测试备件档案创建、编码防重校验、低库存标记与安全库存预警")
    @Transactional
    public void testSparePartCrudAndLowStockAlert() {
        String uniqueCode = "PART_TEST_" + System.currentTimeMillis();

        // 1. 创建物料档案，初始库存为 0，阈值为 10
        SparePartCreateUpdateRequest req = new SparePartCreateUpdateRequest();
        req.setPartCode(uniqueCode);
        req.setPartName("测试LED超薄筒灯");
        req.setCategoryType("电工电气");
        req.setSpecification("12W 4000K开孔10cm");
        req.setUnit("套");
        req.setGuidePrice(new BigDecimal("28.50"));
        req.setSafetyStockThreshold(10);
        req.setStatus(1);

        SparePartVO created = inventoryService.saveOrUpdateSparePart(req);
        Assertions.assertNotNull(created.getId());
        Assertions.assertEquals(0, created.getStockQuantity());
        Assertions.assertTrue(created.getIsLowStock(), "初始库存为0，低于安全阈值10，必须标记为低库存预警");

        // 2. 防重校验：使用同一编码再次创建必须拦截
        Assertions.assertThrows(Exception.class, () -> {
            SparePartCreateUpdateRequest dupReq = new SparePartCreateUpdateRequest();
            dupReq.setPartCode(uniqueCode);
            dupReq.setPartName("重复编码筒灯");
            dupReq.setCategoryType("电工电气");
            dupReq.setUnit("套");
            dupReq.setGuidePrice(new BigDecimal("20.00"));
            dupReq.setSafetyStockThreshold(5);
            dupReq.setStatus(1);
            inventoryService.saveOrUpdateSparePart(dupReq);
        });

        // 3. 分页查询低库存
        SparePartQueryRequest queryReq = new SparePartQueryRequest();
        queryReq.setKeyword(uniqueCode);
        queryReq.setLowStockOnly(true);
        Page<SparePartVO> pageRes = inventoryService.getSparePartsPage(queryReq);
        Assertions.assertEquals(1, pageRes.getTotal());
        Assertions.assertEquals(uniqueCode, pageRes.getRecords().get(0).getPartCode());
    }

    @Test
    @DisplayName("测试采购批量入库事务原子累加物理库存与流水可追溯性")
    @Transactional
    public void testInboundRegistrationAndStockIncrement() {
        String code = "INB_TEST_" + System.currentTimeMillis();

        SparePartCreateUpdateRequest createReq = new SparePartCreateUpdateRequest();
        createReq.setPartCode(code);
        createReq.setPartName("PPR防冻热熔阀门25mm");
        createReq.setCategoryType("水暖管件");
        createReq.setUnit("个");
        createReq.setGuidePrice(new BigDecimal("45.00"));
        createReq.setSafetyStockThreshold(8);
        createReq.setStatus(1);

        SparePartVO part = inventoryService.saveOrUpdateSparePart(createReq);
        Assertions.assertEquals(0, part.getStockQuantity());

        // 执行批次入库 50 件
        SparePartInboundRequest inReq = new SparePartInboundRequest();
        inReq.setPartId(part.getId());
        inReq.setQuantity(50);
        inReq.setUnitPrice(new BigDecimal("38.00"));
        inReq.setBatchDate(LocalDate.now());
        inReq.setRemark("2026秋季学期第一批常规水暖集采");

        SparePartInbound inbound = inventoryService.registerInbound(1L, "后勤采购员王老师", inReq);
        Assertions.assertNotNull(inbound.getId());
        Assertions.assertNotNull(inbound.getInboundNo());
        Assertions.assertEquals(new BigDecimal("1900.00"), inbound.getTotalAmount());

        // 验证物理表库存已被原子增加至 50
        SparePart updated = sparePartMapper.selectById(part.getId());
        Assertions.assertEquals(50, updated.getStockQuantity(), "入库后实际物理库存应累加为 50");

        // 查询入库流水追溯
        List<SparePartInbound> history = inventoryService.getInboundHistoryByPartId(part.getId());
        Assertions.assertEquals(1, history.size());
        Assertions.assertEquals(inbound.getInboundNo(), history.get(0).getInboundNo());
    }

    @Test
    @DisplayName("测试缺料采购补货建议计算公式与库存概况指标统计")
    @Transactional
    public void testPurchaseAdviceCalculationAndSummary() {
        String codeLow = "ADVICE_LOW_" + System.currentTimeMillis();

        // 录入一个安全库存 20、当前库存 5 的缺料备件
        SparePartCreateUpdateRequest req = new SparePartCreateUpdateRequest();
        req.setPartCode(codeLow);
        req.setPartName("特种高压空气开关");
        req.setCategoryType("电工电气");
        req.setUnit("只");
        req.setGuidePrice(new BigDecimal("120.00"));
        req.setSafetyStockThreshold(20);
        req.setStatus(1);

        SparePartVO p = inventoryService.saveOrUpdateSparePart(req);

        // 先入库 5 只，依然低于安全水位 20
        SparePartInboundRequest in = new SparePartInboundRequest();
        in.setPartId(p.getId());
        in.setQuantity(5);
        in.setUnitPrice(new BigDecimal("110.00"));
        inventoryService.registerInbound(1L, "管理员", in);

        // 1. 获取库存指标概况
        InventorySummaryVO summary = inventoryService.getInventorySummary();
        Assertions.assertTrue(summary.getTotalPartCount() >= 1);
        Assertions.assertTrue(summary.getLowStockCount() >= 1);

        // 2. 获取采购建议清单
        List<PurchaseAdviceExportVO> adviceList = inventoryService.getPurchaseAdviceList("电工电气");
        Assertions.assertNotNull(adviceList);

        PurchaseAdviceExportVO targetAdvice = adviceList.stream()
                .filter(a -> codeLow.equals(a.getPartCode()))
                .findFirst()
                .orElse(null);

        Assertions.assertNotNull(targetAdvice, "必须包含低库存缺料物料的采购建议");
        Assertions.assertEquals(5, targetAdvice.getStockQuantity());
        Assertions.assertEquals(20, targetAdvice.getSafetyStockThreshold());
        Assertions.assertEquals(15, targetAdvice.getShortageQuantity(), "缺货缺口数 = 20 - 5 = 15");
        // 建议采购量至 2 倍安全水位 (2 * 20 - 5 = 35)
        Assertions.assertEquals(35, targetAdvice.getRecommendedPurchaseQuantity(), "推荐采购量至2倍安全水位");
        Assertions.assertEquals(new BigDecimal("4200.00"), targetAdvice.getEstimatedTotalAmount(), "预计采购金额 = 35 * 120 = 4200");
    }
}
