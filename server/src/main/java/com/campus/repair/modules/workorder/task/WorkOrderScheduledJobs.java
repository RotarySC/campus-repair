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
    private final com.campus.repair.modules.notification.service.NotificationService notificationService;

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

    /**
     * SLA 超时预警与催办报警扫描任务: 每10分钟扫描一次进行中或待处理但已超出 slaDeadline 的未结单工单
     */
    @Scheduled(cron = "0 0/10 * * * ?")
    public void scanSlaTimeoutAlerts() {
        LocalDateTime now = LocalDateTime.now();
        List<RepairOrder> breachedOrders = repairOrderMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RepairOrder>()
                        .in(RepairOrder::getStatus, List.of("PENDING_DISPATCH", "MANUAL_DISPATCH", "ASSIGNED", "IN_PROGRESS"))
                        .isNotNull(RepairOrder::getSlaDeadline)
                        .le(RepairOrder::getSlaDeadline, now)
                        .eq(RepairOrder::getIsSlaTimeout, 0)
        );

        if (breachedOrders == null || breachedOrders.isEmpty()) {
            return;
        }

        log.warn("[SLA预警引擎] 扫描到 {} 张已超出承诺时效的活跃工单，准备触发 SLA_ALERT 告警通知", breachedOrders.size());
        for (RepairOrder order : breachedOrders) {
            try {
                // 标记为已超时
                order.setIsSlaTimeout(1);
                repairOrderMapper.updateById(order);

                // 1. 通知维修师傅（若已指派）
                if (order.getWorkerId() != null) {
                    String title = "【SLA超时急办预警】工单处理已逾期";
                    String content = String.format("工单 [%s] (%s) SLA 截止时间已超出 (%s)，请加快排障处理进度！",
                            order.getOrderNo(), order.getCategoryFullName(), order.getSlaDeadline());
                    notificationService.sendNotification(order.getWorkerId(), title, content, "SLA_ALERT", order.getId());
                }

                // 2. 通知报修师生
                if (order.getStudentUserId() != null) {
                    String title = "【SLA时效关怀】您的报修单正在催办中";
                    String content = String.format("您的报修单 [%s] 已超出预估时效，后勤调度中心已向责任网格师傅发出加急督促，我们将全力保障维修质量。",
                            order.getOrderNo());
                    notificationService.sendNotification(order.getStudentUserId(), title, content, "SLA_ALERT", order.getId());
                }
            } catch (Exception e) {
                log.error("[SLA预警引擎] 处理工单 {} SLA超时告警发生异常", order.getOrderNo(), e);
            }
        }
    }
}
