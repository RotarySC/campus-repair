package com.campus.repair.modules.workorder.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class OrderCreatedEvent extends ApplicationEvent {

    private final Long orderId;
    private final String orderNo;

    public OrderCreatedEvent(Object source, Long orderId, String orderNo) {
        super(source);
        this.orderId = orderId;
        this.orderNo = orderNo;
    }
}
