package com.campus.repair.modules.review.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 公告审核：结论 APPROVED / REJECTED；驳回时理由必填（Service 判，≤500）。 */
@Data
public class AnnouncementReviewRequest {

    @NotBlank(message = "审核结论不能为空")
    private String decision;

    @Size(max = 500, message = "审核意见不能超过 500 字")
    private String reason;
}
