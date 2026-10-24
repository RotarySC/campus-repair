package com.campus.repair.modules.audit.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 字典/参数变更审计行（{@code sys_operation_log}）。**append-only**：
 * 只有 {@code insert} 与分页读，没有 update/delete 的方法与端点。
 *
 * <p>前后值快照是**白名单字段的 JSON 字符串**（见 {@code OperationSnapshotMaps}），
 * 不是整实体序列化——避免把 {@code deleted}、内部标志、无意义时间戳写进日志。
 */
@Data
@TableName("sys_operation_log")
public class OperationLog implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** SPATIAL / CATEGORY / CONFIG */
    private String module;

    /** CREATE / UPDATE / DELETE / STATUS / IMPORT */
    private String action;

    /** SPATIAL_NODE / REPAIR_CATEGORY / SYS_CONFIG */
    private String targetType;

    /** 目标主键；删除后仍保留（删除时先取旧值） */
    private Long targetId;

    /** 冗余名称，删除后仍可读 */
    private String targetName;

    /** 白名单字段 JSON；CREATE 时为 null */
    private String beforeSnapshot;

    /** 白名单字段 JSON；DELETE 时为 null */
    private String afterSnapshot;

    private Long operatorId;

    private String operatorName;

    /** DISPATCHER / PLATFORM_ADMIN，须在 {@code IdentityCodes.ALL_AUDIT_ROLES} 内 */
    private String operatorRole;

    private String remark;

    private LocalDateTime createdAt;
}
