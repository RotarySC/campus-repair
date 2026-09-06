package com.campus.repair.modules.workorder.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkOrderSubmitResultVO {
    private Long orderId;
    private String orderNo;
    private String status;
}
