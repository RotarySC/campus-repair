package com.campus.repair.modules.workorder.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class OrderPendingAcceptanceEvent extends ApplicationEvent {

    private final Long orderId;
    private final String orderNo;
    private final Long studentUserId;

    public OrderPendingAcceptanceEvent(Object source, Long orderId, String orderNo, Long studentUserId) {
        super(source);
        this.orderId = orderId;
        this.orderNo = orderNo;
        this.studentUserId = studentUserId;
    }
}
