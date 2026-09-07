package com.campus.repair.modules.workorder.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class WorkerOrderListRequest implements Serializable {

    /**
     * 工作台栏目 Tab:
     * ASSIGNED: 待承接 (新分派到自己的工单)
     * IN_PROGRESS: 维修中 (包含 IN_PROGRESS 和 SUSPENDED 挂起工单)
     * HISTORY: 历史记录 (包含 PENDING_ACCEPTANCE, COMPLETED, REWORK_PENDING, CANCELLED)
     */
    private String tab = "ASSIGNED";

    private Integer page = 1;

    private Integer size = 20;

    /**
     * 关键字过滤 (报修单号/报修人/描述/位置)
     */
    private String keyword;
}
