package com.campus.repair.modules.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.repair.modules.workorder.entity.RepairOrder;
import com.campus.repair.modules.workorder.entity.RepairOrderLog;
import com.campus.repair.modules.workorder.mapper.RepairOrderLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 不可篡改的工单流转审计写入与读取。
 *
 * <p>{@code operatorRole} 列的取值域是 {@code IdentityCodes.ALL_AUDIT_ROLES}
 * （4 个职能码 + {@code SYSTEM}），语义是「**该动作是以哪种职能身份执行的**」。
 * 批次 4 起运行时只写这一套词表；改名前的历史行仍是旧角色码，由前端
 * {@code OperatorRoleLabels} 的兼容映射翻译，**不重写历史**。
 * 参数为 null 时的兜底值是 {@code SYSTEM}（而不是某个具体角色码，避免凭空冒充一个人）。
 */
@Service
@RequiredArgsConstructor
public class WorkOrderLogService {

    private final RepairOrderLogMapper logMapper;

    public void recordLog(RepairOrder order, Long operatorId, String operatorName, String operatorRole,
                          String actionType, String fromStatus, String toStatus, String remark) {
        recordLog(order, operatorId, operatorName, operatorRole, actionType, fromStatus, toStatus, remark, order.getFaultImages());
    }

    public void recordLog(RepairOrder order, Long operatorId, String operatorName, String operatorRole,
                          String actionType, String fromStatus, String toStatus, String remark, String evidenceImages) {
        RepairOrderLog log = new RepairOrderLog();
        log.setOrderId(order.getId());
        log.setOrderNo(order.getOrderNo());
        log.setOperatorId(operatorId != null ? operatorId : 0L);
        log.setOperatorName(operatorName != null ? operatorName : "系统");
        log.setOperatorRole(operatorRole != null ? operatorRole : "SYSTEM");
        log.setActionType(actionType);
        log.setFromStatus(fromStatus);
        log.setToStatus(toStatus);
        log.setReasonOrRemark(remark);
        log.setEvidenceImages(evidenceImages != null ? evidenceImages : order.getFaultImages());
        log.setCreatedAt(LocalDateTime.now());

        logMapper.insert(log);
    }

    public List<RepairOrderLog> getTimeline(Long orderId) {
        return logMapper.selectList(new LambdaQueryWrapper<RepairOrderLog>()
                .eq(RepairOrderLog::getOrderId, orderId)
                .orderByAsc(RepairOrderLog::getCreatedAt));
    }

    public long calculateSuspendDurationMinutes(Long orderId) {
        List<RepairOrderLog> logs = getTimeline(orderId);
        if (logs == null || logs.isEmpty()) {
            return 1;
        }
        for (int i = logs.size() - 1; i >= 0; i--) {
            RepairOrderLog l = logs.get(i);
            if ("SUSPEND".equals(l.getActionType())) {
                long mins = java.time.Duration.between(l.getCreatedAt(), LocalDateTime.now()).toMinutes();
                return mins < 1 ? 1 : mins;
            }
        }
        return 1;
    }
}
