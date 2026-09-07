package com.campus.repair.modules.inventory.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import com.campus.repair.modules.inventory.entity.SparePart;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseAdviceExportVO {

    @ExcelProperty("物料编码")
    @ColumnWidth(18)
    private String partCode;

    @ExcelProperty("备件物料名称")
    @ColumnWidth(25)
    private String partName;

    @ExcelProperty("耗材类别")
    @ColumnWidth(16)
    private String categoryType;

    @ExcelProperty("规格型号")
    @ColumnWidth(20)
    private String specification;

    @ExcelProperty("计量单位")
    @ColumnWidth(10)
    private String unit;

    @ExcelProperty("当前物理库存")
    @ColumnWidth(14)
    private Integer stockQuantity;

    @ExcelProperty("安全库存警戒线")
    @ColumnWidth(16)
    private Integer safetyStockThreshold;

    @ExcelProperty("缺货缺口数")
    @ColumnWidth(14)
    private Integer shortageQuantity;

    @ExcelProperty("建议采购量(2倍安全水位)")
    @ColumnWidth(24)
    private Integer recommendedPurchaseQuantity;

    @ExcelProperty("参考指导单价(元)")
    @ColumnWidth(18)
    private BigDecimal guidePrice;

    @ExcelProperty("预计采购金额(元)")
    @ColumnWidth(18)
    private BigDecimal estimatedTotalAmount;

    public static PurchaseAdviceExportVO fromSparePart(SparePart p) {
        int stock = p.getStockQuantity() != null ? p.getStockQuantity() : 0;
        int threshold = p.getSafetyStockThreshold() != null ? p.getSafetyStockThreshold() : 0;
        int shortage = Math.max(0, threshold - stock);
        int recommended = Math.max(threshold, (threshold * 2) - stock);
        BigDecimal price = p.getGuidePrice() != null ? p.getGuidePrice() : BigDecimal.ZERO;
        BigDecimal estimatedTotal = price.multiply(BigDecimal.valueOf(recommended));

        return PurchaseAdviceExportVO.builder()
                .partCode(p.getPartCode())
                .partName(p.getPartName())
                .categoryType(p.getCategoryType())
                .specification(p.getSpecification())
                .unit(p.getUnit())
                .stockQuantity(stock)
                .safetyStockThreshold(threshold)
                .shortageQuantity(shortage)
                .recommendedPurchaseQuantity(recommended)
                .guidePrice(price)
                .estimatedTotalAmount(estimatedTotal)
                .build();
    }
}

