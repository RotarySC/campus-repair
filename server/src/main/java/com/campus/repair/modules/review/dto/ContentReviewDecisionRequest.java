package com.campus.repair.modules.review.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 复核动作的请求体。
 *
 * <p>通过时 {@code reason} 可省略；驳回时必填（≤500）。「必填」由 Service 判而不是注解判，
 * 因为同一个 DTO 服务两个端点：加 {@code @NotBlank} 会把「通过」也拦成 400。
 */
@Data
@Schema(description = "内容复核请求")
public class ContentReviewDecisionRequest {

    @Schema(description = "审核意见：通过可空；驳回必填，≤500 字")
    @Size(max = 500, message = "审核意见不能超过 500 字")
    private String reason;
}
