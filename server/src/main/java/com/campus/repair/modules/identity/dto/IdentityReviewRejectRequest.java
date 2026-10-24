package com.campus.repair.modules.identity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 驳回身份核验申请的请求体：**理由必填**（用户要靠它知道补什么材料）。 */
@Data
public class IdentityReviewRejectRequest {

    @NotBlank(message = "驳回理由不能为空")
    @Size(max = 255, message = "驳回理由不能超过 255 字")
    private String reason;
}
