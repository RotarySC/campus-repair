package com.campus.repair.modules.workorder.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 「按工单状态分组计数」的聚合行对象（`GROUP BY status` 的去重取值）。
 * 状态分桶由调用方在 Java 侧归并，SQL 只负责分组计数。
 */
@Data
public class OrderStatusCountRow implements Serializable {

    private String status;

    /** 对应 SQL 别名 `status_count`（`map-underscore-to-camel-case: true` 自动映射） */
    private Integer statusCount;
}
