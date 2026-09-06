package com.campus.repair.modules.workorder.service;

import com.campus.repair.common.exception.BusinessException;
import com.campus.repair.common.exception.ErrorCode;
import com.campus.repair.modules.workorder.enums.OrderStatusEnum;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

@Component
public class WorkOrderStateMachine {

    private static final Map<OrderStatusEnum, Set<OrderStatusEnum>> VALID_TRANSITIONS = new EnumMap<>(OrderStatusEnum.class);

    static {
        VALID_TRANSITIONS.put(OrderStatusEnum.DRAFT, Set.of(OrderStatusEnum.PENDING_DISPATCH));
        VALID_TRANSITIONS.put(OrderStatusEnum.PENDING_DISPATCH, Set.of(OrderStatusEnum.ASSIGNED, OrderStatusEnum.MANUAL_DISPATCH));
        VALID_TRANSITIONS.put(OrderStatusEnum.MANUAL_DISPATCH, Set.of(OrderStatusEnum.ASSIGNED, OrderStatusEnum.CANCELLED));
        VALID_TRANSITIONS.put(OrderStatusEnum.ASSIGNED, Set.of(OrderStatusEnum.IN_PROGRESS, OrderStatusEnum.MANUAL_DISPATCH));
        VALID_TRANSITIONS.put(OrderStatusEnum.IN_PROGRESS, Set.of(OrderStatusEnum.SUSPENDED, OrderStatusEnum.PENDING_ACCEPTANCE, OrderStatusEnum.MANUAL_DISPATCH));
        VALID_TRANSITIONS.put(OrderStatusEnum.SUSPENDED, Set.of(OrderStatusEnum.IN_PROGRESS));
        VALID_TRANSITIONS.put(OrderStatusEnum.PENDING_ACCEPTANCE, Set.of(OrderStatusEnum.COMPLETED, OrderStatusEnum.REWORK_PENDING));
        VALID_TRANSITIONS.put(OrderStatusEnum.REWORK_PENDING, Set.of(OrderStatusEnum.ASSIGNED, OrderStatusEnum.CANCELLED));
    }

    public void verifyTransition(OrderStatusEnum from, OrderStatusEnum to) {
        Set<OrderStatusEnum> allowed = VALID_TRANSITIONS.get(from);
        if (allowed == null || !allowed.contains(to)) {
            throw new BusinessException(ErrorCode.ORDER_STATE_TRANSITION_INVALID.getCode(),
                    String.format("工单状态流转非法: 禁止从 [%s] 跃迁至 [%s]", from.name(), to.name()));
        }
    }
}
