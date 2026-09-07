package com.campus.repair.modules.workorder.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class OrderReworkTriggeredEvent extends ApplicationEvent {

    private final Long orderId;
    private final String orderNo;
    private final Long studentUserId;
    private final Integer ratingStar;
    private final String reason;

    public OrderReworkTriggeredEvent(Object source, Long orderId, String orderNo, Long studentUserId, Integer ratingStar, String reason) {
        super(source);
        this.orderId = orderId;
        this.orderNo = orderNo;
        this.studentUserId = studentUserId;
        this.ratingStar = ratingStar;
        this.reason = reason;
    }
}
