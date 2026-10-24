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

    /**
     * 内容审核状态：PASSED（已放行，正常派单）/ PENDING（初筛命中，待人工复核，暂不派单）。
     *
     * <p>前端据此提示「内容审核通过后自动派单」；旧前端不读该字段也不受影响。
     */
    private String reviewStatus;

    /**
     * 命中规则的**分类摘要**（如 `内容含广告导流/他人隐私，待人工复核`），未命中为 null。
     *
     * <p>刻意不回传词表原文与精确规则：那等于教人绕过初筛。
     */
    private String reviewReason;
}
