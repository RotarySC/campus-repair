package com.campus.repair.modules.user.vo;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
public class WorkerCandidateVO implements Serializable {
    private Long id;
    private Long userId;
    private String username;
    private String realName;
    private String phone;
    private String skillCode;
    private Long campusId;
    private Integer maxLoad;
    private Integer currentLoad;
    private Integer onDutyStatus;
    private BigDecimal ratingAvg;
    private Integer totalCompletedCount;
}
