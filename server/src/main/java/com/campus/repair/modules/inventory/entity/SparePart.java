package com.campus.repair.modules.inventory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("spare_part")
public class SparePart implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String partCode;

    private String partName;

    private String categoryType;

    private String specification;

    private String unit;

    private BigDecimal guidePrice;

    private Integer stockQuantity;

    private Integer safetyStockThreshold;

    private Integer status; // 1-正常在架, 0-停用下架

    /**
     * 库存告警冷却水位：上次向调度/平台管理推送 {@code STOCK_WARNING} 的时间。
     * {@code null}=从未告警（或入库补回阈值以上后被清空）。
     */
    private LocalDateTime lastWarningAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
