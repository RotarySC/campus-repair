package com.campus.repair.modules.analytics.dto;

import lombok.Data;

import java.util.List;

@Data
public class WorkOrderFilterQueryRequest {

    private Integer page = 1;

    private Integer size = 20;

    /**
     * 关键词（单号、描述、报修人、联系电话、师傅名）
     */
    private String keyword;

    /**
     * 空间节点ID
     */
    private Long spaceId;

    /**
     * 故障分类ID
     */
    private Long categoryId;

    /**
     * 指定维修师傅ID
     */
    private Long workerId;

    /**
     * 状态多选列表
     */
    private List<String> statuses;

    /**
     * 是否加急: 0-否, 1-是
     */
    private Integer isUrgent;

    /**
     * 归档状态: null 为全部活跃, 0-未归档活跃, 1-已归档冷数据
     */
    private Integer isArchived = 0;

    /**
     * 报修时间起 (yyyy-MM-dd HH:mm:ss 或 yyyy-MM-dd)
     */
    private String createTimeStart;

    /**
     * 报修时间止
     */
    private String createTimeEnd;
}
