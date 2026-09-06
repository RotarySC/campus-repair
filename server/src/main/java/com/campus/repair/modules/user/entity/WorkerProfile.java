package com.campus.repair.modules.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("worker_profile")
public class WorkerProfile implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String skillCode; // ELECTRICIAN, PLUMBER, HVAC, LOCKSMITH, NETWORK

    private Long campusId; // 所属校区ID

    private Integer maxLoad; // 并发承接上限 (默认10)

    private Integer currentLoad; // 当前在修及挂起量

    private Integer onDutyStatus; // 考勤状态: 1-在岗, 0-休息

    private BigDecimal ratingAvg; // 综合评分均值 (默认5.00)

    private Integer totalCompletedCount; // 历史完工单数

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
