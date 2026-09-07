package com.campus.repair.modules.workorder.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class OrderCancelledEvent extends ApplicationEvent {

    private final Long orderId;
    private final String orderNo;
    private final Long studentUserId;
    private final String cancelReason;

    public OrderCancelledEvent(Object source, Long orderId, String orderNo, Long studentUserId, String cancelReason) {
        super(source);
        this.orderId = orderId;
        this.orderNo = orderNo;
        this.studentUserId = studentUserId;
        this.cancelReason = cancelReason;
    }
}
