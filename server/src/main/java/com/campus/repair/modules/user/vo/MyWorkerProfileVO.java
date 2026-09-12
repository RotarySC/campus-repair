package com.campus.repair.modules.user.vo;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 师傅「我的作业概览」（个人中心统计区）。
 *
 * <p>只读视图：`onDutyStatus` 本轮仅供展示（自助切换会改变自动派单候选，属独立的业务决策）。
 * `ratingAvg` 直接取库值（`worker_profile.rating_avg` 列默认 5.00），前端在
 * `totalCompletedCount == 0` 时渲染为 `—`，避免「零完工也显示 5 星」虚增口碑。
 */
@Data
@Builder
public class MyWorkerProfileVO implements Serializable {

    private String skillCode;
    private Long campusId;
    private String campusName;
    private Integer maxLoad;
    private Integer currentLoad;
    /** 0~100，与调度中心技师负荷看板同一口径 */
    private Integer loadRate;
    /** 1-在岗, 0-休息 */
    private Integer onDutyStatus;
    private BigDecimal ratingAvg;
    private Integer totalCompletedCount;
    private Integer todayCompletedCount;
}
