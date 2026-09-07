package com.campus.repair.modules.inventory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("repair_material_consume")
public class RepairMaterialConsume implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    private String orderNo;

    private Long workerId;

    private Long partId;

    private String partName;

    private Integer consumeQuantity;

    private BigDecimal unitPrice;

    private BigDecimal totalCost;

    private LocalDateTime createdAt;
}
