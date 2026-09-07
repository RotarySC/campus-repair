package com.campus.repair.modules.workorder.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campus.repair.modules.workorder.entity.RepairOrder;
import com.campus.repair.modules.workorder.entity.RepairOrderLog;
import com.campus.repair.modules.workorder.mapper.RepairOrderLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkOrderLogService {

    private final RepairOrderLogMapper logMapper;

    public void recordLog(RepairOrder order, Long operatorId, String operatorName, String operatorRole,
                          String actionType, String fromStatus, String toStatus, String remark) {
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
        log.setEvidenceImages(order.getFaultImages());
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
