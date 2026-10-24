package com.campus.repair.modules.audit.dto;

import lombok.Data;

/**
 * 审计分页查询参数。
 *
 * <p>时间列为字符串而不用 {@code LocalDateTime}：前端日期选择器可能只给 {@code yyyy-MM-dd}，
 * 服务层按「日期串 / ISO 时间 / 空格时间」三种写法宽容解析（见 {@code OperationLogService}）。
 */
@Data
public class OperationLogQueryRequest {

    /** SPATIAL / CATEGORY / CONFIG；为空或 ALL 表示不过滤 */
    private String module;

    /** SPATIAL_NODE / REPAIR_CATEGORY / SYS_CONFIG */
    private String targetType;

    private Long targetId;

    /** 起始时间（含）；支持 yyyy-MM-dd / yyyy-MM-dd HH:mm:ss / ISO */
    private String start;

    /** 结束时间（含）；只给日期时取当日 23:59:59 */
    private String end;

    private Integer page;

    private Integer size;
}
