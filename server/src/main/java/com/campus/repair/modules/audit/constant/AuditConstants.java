package com.campus.repair.modules.audit.constant;

/**
 * 字典/参数变更审计的词表（{@code sys_operation_log.module/action/target_type} 的取值来源）。
 *
 * <p>取值与 `docs/phases/phase-04-dict-audit-and-stock-warning.md` §3.1 的列注释一致：
 * <ul>
 *   <li>{@code module}：SPATIAL / CATEGORY / CONFIG（页面抽屉的筛选维度）</li>
 *   <li>{@code action}：CREATE / UPDATE / DELETE / STATUS / IMPORT</li>
 *   <li>{@code target_type}：SPATIAL_NODE / REPAIR_CATEGORY / SYS_CONFIG</li>
 * </ul>
 *
 * <p>与 {@code IdentityCodes} 同一做法：这里只给服务层与查询参数用；
 * 审计行本身 append-only，没有 update/delete 端点。
 */
public final class AuditConstants {

    private AuditConstants() {
    }

    // --- module ---
    public static final String MODULE_SPATIAL = "SPATIAL";
    public static final String MODULE_CATEGORY = "CATEGORY";
    public static final String MODULE_CONFIG = "CONFIG";

    // --- action ---
    public static final String ACTION_CREATE = "CREATE";
    public static final String ACTION_UPDATE = "UPDATE";
    public static final String ACTION_DELETE = "DELETE";
    public static final String ACTION_STATUS = "STATUS";
    public static final String ACTION_IMPORT = "IMPORT";

    // --- target type ---
    public static final String TARGET_SPATIAL_NODE = "SPATIAL_NODE";
    public static final String TARGET_REPAIR_CATEGORY = "REPAIR_CATEGORY";
    public static final String TARGET_SYS_CONFIG = "SYS_CONFIG";
}
