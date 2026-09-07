package com.campus.repair.modules.notification.listener;

import com.campus.repair.modules.notification.service.NotificationService;
import com.campus.repair.modules.workorder.entity.RepairOrder;
import com.campus.repair.modules.workorder.event.OrderAssignedEvent;
import com.campus.repair.modules.workorder.event.OrderCancelledEvent;
import com.campus.repair.modules.workorder.event.OrderCreatedEvent;
import com.campus.repair.modules.workorder.event.OrderPendingAcceptanceEvent;
import com.campus.repair.modules.workorder.event.OrderReworkTriggeredEvent;
import com.campus.repair.modules.workorder.mapper.RepairOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class OrderNotificationEventListener {

    private final NotificationService notificationService;
    private final RepairOrderMapper orderMapper;

    /**
     * 工单创建通知 (提单师生)
     */
    @Async
    @EventListener
    public void handleOrderCreated(OrderCreatedEvent event) {
        log.info("[OrderNotificationEventListener] 监听到提单事件: orderNo={}", event.getOrderNo());
        RepairOrder order = orderMapper.selectById(event.getOrderId());
        if (order != null && order.getStudentUserId() != null) {
            String title = "报修提交成功提醒";
            String content = String.format("您的报修单 [%s] (%s) 已成功提交，系统正在为您进行智能调度分配师傅，请耐心等待。",
                    order.getOrderNo(), order.getCategoryFullName() != null ? order.getCategoryFullName() : "设施故障");
            notificationService.sendNotification(order.getStudentUserId(), title, content, "ORDER_FLOW", order.getId());
        }
    }

    /**
     * 工单指派通知 (师傅收到新任务通知 + 师生收到师傅指派通知)
     */
    @Async
    @EventListener
    public void handleOrderAssigned(OrderAssignedEvent event) {
        log.info("[OrderNotificationEventListener] 监听到工单指派事件: orderNo={}, workerId={}", event.getOrderNo(), event.getWorkerId());
        RepairOrder order = orderMapper.selectById(event.getOrderId());
        if (order == null) return;

        // 1. 通知维修师傅
        if (event.getWorkerId() != null) {
            String title = "新维修工单指派提醒";
            String content = String.format("您有新的维修工单待承接: 单号 [%s]，地点: %s，报修故障: %s，请及时查看并接单出发！",
                    order.getOrderNo(), order.getSpaceFullPath(), order.getCategoryFullName());
            notificationService.sendNotification(event.getWorkerId(), title, content, "ORDER_DISPATCH", order.getId());
        }

        // 2. 通知报修师生
        if (order.getStudentUserId() != null) {
            String title = "工单已派工提醒";
            String content = String.format("您的报修单 [%s] 已指派维修师傅【%s】为您服务，师傅将尽快接单并上门排障。",
                    order.getOrderNo(), event.getWorkerName() != null ? event.getWorkerName() : "专业维修师傅");
            notificationService.sendNotification(order.getStudentUserId(), title, content, "ORDER_FLOW", order.getId());
        }
    }

    /**
     * 完工待验收通知 (提醒师生核验与评价)
     */
    @Async
    @EventListener
    public void handleOrderPendingAcceptance(OrderPendingAcceptanceEvent event) {
        log.info("[OrderNotificationEventListener] 监听到完工待验收事件: orderNo={}", event.getOrderNo());
        if (event.getStudentUserId() != null) {
            String title = "报修完工待验收提醒";
            String content = String.format("您的报修单 [%s] 师傅已现场维修完成并提交完工实况照片，请您前往详情页核验施工质量并进行满意度评价。",
                    event.getOrderNo());
            notificationService.sendNotification(event.getStudentUserId(), title, content, "ORDER_FLOW", event.getOrderId());
        }
    }

    /**
     * 工单作废通知 (通知师生)
     */
    @Async
    @EventListener
    public void handleOrderCancelled(OrderCancelledEvent event) {
        log.info("[OrderNotificationEventListener] 监听到工单作废事件: orderNo={}", event.getOrderNo());
        if (event.getStudentUserId() != null) {
            String title = "工单核实作废提醒";
            String content = String.format("您的报修单 [%s] 已由后勤调度中心核实作废。作废原因: %s。如有疑问可联系后勤服务热线。",
                    event.getOrderNo(), event.getCancelReason() != null ? event.getCancelReason() : "无");
            notificationService.sendNotification(event.getStudentUserId(), title, content, "ORDER_FLOW", event.getOrderId());
        }
    }

    /**
     * 差评返工通知 (通知师生返工已启动，同时可通知师傅关注整改)
     */
    @Async
    @EventListener
    public void handleOrderReworkTriggered(OrderReworkTriggeredEvent event) {
        log.info("[OrderNotificationEventListener] 监听到差评返工事件: orderNo={}", event.getOrderNo());
        if (event.getStudentUserId() != null) {
            String title = "差评返工督办已启动";
            String content = String.format("您对工单 [%s] 评价反馈为 %d 星，系统已将该工单置顶转入后勤主管返工督办流程，主管将安排师傅尽快再次上门整改。",
                    event.getOrderNo(), event.getRatingStar());
            notificationService.sendNotification(event.getStudentUserId(), title, content, "REWORK_ALERT", event.getOrderId());
        }
    }
}
