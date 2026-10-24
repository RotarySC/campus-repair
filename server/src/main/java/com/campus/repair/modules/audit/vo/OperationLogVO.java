package com.campus.repair.modules.audit.vo;

import lombok.Data;

import java.util.Map;

/**
 * 审计行的展示视图：前后值快照在**读出时**反序列化成对象，
 * 前端拿到的就是可直接对比的字段映射（不是二次转义的 JSON 字符串）。
 */
@Data
public class OperationLogVO {

    private Long id;

    private String module;

    private String action;

    private String targetType;

    private Long targetId;

    private String targetName;

    /** CREATE 时为 null */
    private Map<String, Object> before;

    /** DELETE 时为 null */
    private Map<String, Object> after;

    private Long operatorId;

    private String operatorName;

    private String operatorRole;

    private String remark;

    /** 统一格式：yyyy-MM-dd HH:mm:ss */
    private String createdAt;
}
