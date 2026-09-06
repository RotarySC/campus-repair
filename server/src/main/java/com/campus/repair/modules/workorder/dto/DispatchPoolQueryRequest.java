package com.campus.repair.modules.workorder.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class DispatchPoolQueryRequest implements Serializable {

    /**
     * 页码，默认 1
     */
    private Integer page = 1;

    /**
     * 每页数量，默认 20
     */
    private Integer size = 20;

    /**
     * 分类过滤快捷标签：
     * ALL: 全部待调度工单 (MANUAL_DISPATCH / REWORK_PENDING 等)
     * DISPATCH_FAIL: 自动派单失败 (MANUAL_DISPATCH 且 dispatch_type = MANUAL)
     * TRANSFER_APPLY: 师傅申请转派 (由师傅申请转派流入人工调度的工单)
     * URGENT: 加急异常单 (urgency_level = 2)
     * REWORK: 返工待指派 (status = REWORK_PENDING)
     */
    private String categoryFilter;

    /**
     * 紧急程度: 1-普通日常, 2-加急紧急
     */
    private Integer urgencyLevel;

    /**
     * 所属空间节点ID
     */
    private Long spaceId;

    /**
     * 维修工种要求
     */
    private String workerSkillCode;

    /**
     * 关键字模糊搜索 (单号/报修人/描述)
     */
    private String keyword;
}
