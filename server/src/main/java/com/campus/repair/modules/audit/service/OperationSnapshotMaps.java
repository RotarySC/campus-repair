package com.campus.repair.modules.audit.service;

import com.campus.repair.modules.audit.constant.AuditConstants;
import com.campus.repair.modules.spatial.entity.RepairCategory;
import com.campus.repair.modules.spatial.entity.SpatialNode;
import com.campus.repair.modules.spatial.entity.SysConfig;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审计快照的唯一白名单来源：每个 targetType 只把**业务可读字段**抄进 JSON。
 *
 * <p>为什么不用实体 {@code toString}/整实体序列化：实体里有 {@code deleted}、
 * 时间戳等与「谁改了什么」无关的字段，整包写进日志既噪声又容易在实体加字段时
 * 把内部标志泄露到前端（`phase-04` §3.1 的明确要求）。
 *
 * <p>实体新增「需要留痕」的字段时，**只改这一处**：
 * {@code OperationLogIntegrationTest} 会断言关键字段，白名单漏字段能被用例抓到。
 */
public final class OperationSnapshotMaps {

    private OperationSnapshotMaps() {
    }

    public static Map<String, Object> of(String targetType, Object entity) {
        if (entity == null) {
            return null;
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        switch (targetType) {
            case AuditConstants.TARGET_SPATIAL_NODE -> snapshotSpatialNode(snapshot, (SpatialNode) entity);
            case AuditConstants.TARGET_REPAIR_CATEGORY -> snapshotRepairCategory(snapshot, (RepairCategory) entity);
            case AuditConstants.TARGET_SYS_CONFIG -> snapshotSysConfig(snapshot, (SysConfig) entity);
            default -> {
                return null;
            }
        }
        return snapshot;
    }

    /** 空间节点：name / parentId / levelType / buildingNature / fullPathName / sortOrder / status */
    private static void snapshotSpatialNode(Map<String, Object> snapshot, SpatialNode node) {
        snapshot.put("name", node.getName());
        snapshot.put("parentId", node.getParentId());
        snapshot.put("levelType", node.getLevelType());
        snapshot.put("buildingNature", node.getBuildingNature());
        snapshot.put("fullPathName", node.getFullPathName());
        snapshot.put("sortOrder", node.getSortOrder());
        snapshot.put("status", node.getStatus());
    }

    /** 故障分类：name / parentId / level / workerSkillCode / slaHours / keywords / selfHelpTip / sortOrder / status */
    private static void snapshotRepairCategory(Map<String, Object> snapshot, RepairCategory category) {
        snapshot.put("name", category.getName());
        snapshot.put("parentId", category.getParentId());
        snapshot.put("level", category.getLevel());
        snapshot.put("workerSkillCode", category.getWorkerSkillCode());
        snapshot.put("slaHours", category.getSlaHours());
        snapshot.put("keywords", category.getKeywords());
        snapshot.put("selfHelpTip", category.getSelfHelpTip());
        snapshot.put("sortOrder", category.getSortOrder());
        snapshot.put("status", category.getStatus());
    }

    /** 系统配置：configKey / configValue */
    private static void snapshotSysConfig(Map<String, Object> snapshot, SysConfig config) {
        snapshot.put("configKey", config.getConfigKey());
        snapshot.put("configValue", config.getConfigValue());
    }
}
