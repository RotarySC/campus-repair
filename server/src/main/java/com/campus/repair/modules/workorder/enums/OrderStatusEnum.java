package com.campus.repair.modules.workorder.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum OrderStatusEnum {
    DRAFT("草稿"),
    PENDING_DISPATCH("待派单"),
    MANUAL_DISPATCH("待人工调度"),
    ASSIGNED("待承接"),
    IN_PROGRESS("维修中"),
    SUSPENDED("已挂起"),
    PENDING_ACCEPTANCE("待验收"),
    REWORK_PENDING("返工待派"),
    COMPLETED("已结单"),
    CANCELLED("已作废");

    private final String desc;
}
