package com.campus.repair.modules.workorder.task;

import com.campus.repair.modules.spatial.service.SysConfigService;
import com.campus.repair.modules.workorder.entity.RepairOrder;
import com.campus.repair.modules.workorder.mapper.RepairOrderMapper;
import com.campus.repair.modules.workorder.service.WorkOrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkOrderScheduledJobs {

    private final RepairOrderMapper repairOrderMapper;
    private final WorkOrderService workOrderService;
    private final SysConfigService configService;

    /**
     * 72小时超时自动核销任务: 默认每15分钟扫描一次处于 PENDING_ACCEPTANCE 的超时工单
     */
    @Scheduled(cron = "0 0/15 * * * ?")
    public void scanAndAutoCompleteOrders() {
        int timeoutHours = configService.getIntValue("auto_acceptance_timeout_hours", 72);
        LocalDateTime thresholdTime = LocalDateTime.now().minusHours(timeoutHours);

        List<RepairOrder> timeoutOrders = repairOrderMapper.selectPendingAcceptanceTimeoutOrders(thresholdTime);
        if (timeoutOrders == null || timeoutOrders.isEmpty()) {
            return;
        }

        log.info("[定时核销引擎] 扫描到 {} 张待自动核销工单 (阈值: {} 小时前)", timeoutOrders.size(), timeoutHours);
        for (RepairOrder order : timeoutOrders) {
            try {
                workOrderService.autoConfirmAcceptance(order.getId());
                log.info("[定时核销引擎] 工单 {} 超出 {} 小时未确认，已系统自动结单", order.getOrderNo(), timeoutHours);
            } catch (Exception e) {
                log.error("[定时核销引擎] 自动确认工单 {} 发生异常", order.getOrderNo(), e);
            }
        }
    }
}
