package com.campus.repair.modules.workorder.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class OrderAssignedEvent extends ApplicationEvent {

    private final Long orderId;
    private final String orderNo;
    private final Long workerId;
    private final String workerName;
    private final String dispatchType; // AUTO, MANUAL

    public OrderAssignedEvent(Object source, Long orderId, String orderNo, Long workerId, String workerName, String dispatchType) {
        super(source);
        this.orderId = orderId;
        this.orderNo = orderNo;
        this.workerId = workerId;
        this.workerName = workerName;
        this.dispatchType = dispatchType;
    }
}
