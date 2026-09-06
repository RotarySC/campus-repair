package com.campus.repair.modules.workorder.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DuplicateCheckResultVO {

    private boolean hasActiveOrder;
    private String activeOrderNo;
    private String activeOrderStatus;
    private String tipMessage;
}
