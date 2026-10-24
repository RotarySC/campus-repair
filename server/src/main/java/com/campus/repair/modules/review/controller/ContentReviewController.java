package com.campus.repair.modules.review.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campus.repair.common.result.R;
import com.campus.repair.framework.security.LoginUserPrincipal;
import com.campus.repair.modules.review.dto.ContentReviewDecisionRequest;
import com.campus.repair.modules.review.service.ContentReviewService;
import com.campus.repair.modules.review.vo.ContentReviewVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内容复核工作台（初筛 → 复核 → 轨迹）。
 *
 * <p>权限口径：复核由后勤管理员执行，整类收在 {@code DISPATCHER / PLATFORM_ADMIN}；
 * 报修人查看自己被驳回的理由走工单侧 {@code GET /work-orders/{id}/reviews}，
 * 不复用本控制器（避免把队列读权限下放给报修人）。
 */
@Tag(name = "内容审核", description = "初筛命中内容的复核队列、审核轨迹与通过/驳回动作")
@RestController
@RequestMapping("/api/v1/content-reviews")
@PreAuthorize("hasAnyRole('DISPATCHER', 'PLATFORM_ADMIN')")
@RequiredArgsConstructor
public class ContentReviewController {

    private final ContentReviewService contentReviewService;

    @Operation(summary = "分页查询复核队列（outcome 省略=待复核，ALL=全部）")
    @GetMapping
    public R<IPage<ContentReviewVO>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) String keyword) {
        return R.success(contentReviewService.page(page, size, outcome, bizType, keyword));
    }

    @Operation(summary = "审核详情：当前行 + 同一业务对象的全部历史轮次")
    @GetMapping("/{id}")
    public R<ContentReviewVO> detail(@PathVariable Long id) {
        return R.success(contentReviewService.detail(id));
    }

    @Operation(summary = "通过复核（订单补派单 / 公告置 APPROVED）")
    @PostMapping("/{id}/approve")
    public R<ContentReviewVO> approve(@PathVariable Long id,
                                      @Valid @RequestBody(required = false) ContentReviewDecisionRequest request,
                                      @AuthenticationPrincipal LoginUserPrincipal user) {
        String reason = request == null ? null : request.getReason();
        return R.success("已通过复核",
                contentReviewService.approve(id, reason, user.getUserId(), user.getUsername()));
    }

    @Operation(summary = "驳回复核（理由必填，≤500 字）")
    @PostMapping("/{id}/reject")
    public R<ContentReviewVO> reject(@PathVariable Long id,
                                     @Valid @RequestBody ContentReviewDecisionRequest request,
                                     @AuthenticationPrincipal LoginUserPrincipal user) {
        return R.success("已驳回复核",
                contentReviewService.reject(id, request.getReason(), user.getUserId(), user.getUsername()));
    }
}
