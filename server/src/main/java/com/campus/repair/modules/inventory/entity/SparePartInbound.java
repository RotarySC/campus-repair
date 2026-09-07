package com.campus.repair.modules.inventory.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("spare_part_inbound")
public class SparePartInbound implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String inboundNo;

    private Long partId;

    private String partName;

    private LocalDate batchDate;

    private Integer quantity;

    private BigDecimal unitPrice;

    private BigDecimal totalAmount;

    private Long operatorId;

    private String operatorName;

    private String remark;

    private LocalDateTime createdAt;
}
