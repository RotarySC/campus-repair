package com.campus.repair.modules.assistant.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 报修人最近一张工单的进度速查（只取本人的单，天然无 IDOR 面）。 */
@Data
public class AssistantOrderProgressVO implements Serializable {

    private Long orderId;
    private String orderNo;
    private String status;
    /** 状态中文名，来自 {@code OrderStatusEnum} */
    private String statusLabel;
    /** 「下一步该做什么」——由状态机全量映射，缺一即测试失败 */
    private String nextStep;

    private String spaceFullPath;
    private String categoryName;
    private String workerName;
    private String appointmentPeriod;

    private LocalDateTime createdAt;
    private LocalDateTime slaDeadline;

    /** SLA 是否已超时：1-是, 0-否, null-未知 */
    private Integer slaTimeout;
}
