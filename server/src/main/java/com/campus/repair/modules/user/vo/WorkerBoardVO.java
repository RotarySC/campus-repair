package com.campus.repair.modules.user.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
public class WorkerBoardVO implements Serializable {

    private Long id;
    private Long userId;
    private String username;
    private String realName;
    private String phone;
    private String skillCode;
    private Long campusId;
    private String campusName;
    private Integer maxLoad;
    private Integer currentLoad;
    private Integer onDutyStatus; // 1-在岗, 0-休息
    private BigDecimal ratingAvg;
    private Integer totalCompletedCount;

    /**
     * 今日已完工单数
     */
    private Integer todayCompletedCount;

    /**
     * 平均修复时长 (小时，保留1位小数)
     */
    private Double avgRepairHours;

    /**
     * 负荷率百分比 (0~100)
     */
    private Integer loadRate;

    /**
     * 是否已满载 (currentLoad >= maxLoad)
     */
    private Boolean isOverloaded;
}
