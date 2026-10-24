package com.campus.repair.modules.identity.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campus.repair.common.result.R;
import com.campus.repair.framework.security.LoginUserPrincipal;
import com.campus.repair.modules.identity.dto.IdentityReviewRejectRequest;
import com.campus.repair.modules.identity.service.IdentityReviewService;
import com.campus.repair.modules.identity.service.IdentityRosterService;
import com.campus.repair.modules.identity.vo.IdentityImportResultVO;
import com.campus.repair.modules.identity.vo.IdentityReviewVO;
import com.campus.repair.modules.identity.vo.IdentityRosterVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 校内身份核验（Phase 01）。
 *
 * <p>权限口径：整类收在 {@code PLATFORM_ADMIN}（开题写「系统管理员核验」）。
 * **唯一例外**是 {@code GET /reviews/me/latest}——待核验用户自己要看驳回理由与审核进度，
 * 它在 {@code JwtAuthenticationFilter} 的待核验放行名单里；方法级注解会覆盖类级注解，
 * 因此这里显式写 {@code isAuthenticated()}。
 *
 * <p>刻意不放开给 {@code DISPATCHER}：调度可以看名单（只读）方便现场核身，但不能决策。
 * 若答辩要求后勤也能核验，只需把 approve/reject 改成
 * {@code hasAnyRole('PLATFORM_ADMIN','DISPATCHER')}（一行改动）。
 */
@Tag(name = "校内身份核验", description = "名单比对结果的人工核验、人员名单维护与导入")
@RestController
@RequestMapping("/api/v1/identity")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
@RequiredArgsConstructor
public class IdentityController {

    private final IdentityReviewService reviewService;
    private final IdentityRosterService rosterService;

    @Operation(summary = "分页查询身份核验申请（默认只查待办）")
    @GetMapping("/reviews")
    public R<IPage<IdentityReviewVO>> pageReviews(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) String keyword) {
        return R.success(reviewService.pageReviews(page, size, outcome, keyword));
    }

    @Operation(summary = "通过身份核验（一次性决策）")
    @PostMapping("/reviews/{id}/approve")
    public R<Void> approve(@PathVariable Long id, @AuthenticationPrincipal LoginUserPrincipal user) {
        reviewService.approve(id, user.getUserId(), user.getUsername());
        return R.success("核验已通过", null);
    }

    @Operation(summary = "驳回身份核验（一次性决策，理由必填）")
    @PostMapping("/reviews/{id}/reject")
    public R<Void> reject(@PathVariable Long id,
                          @Valid @RequestBody IdentityReviewRejectRequest request,
                          @AuthenticationPrincipal LoginUserPrincipal user) {
        reviewService.reject(id, request.getReason(), user.getUserId(), user.getUsername());
        return R.success("核验已驳回", null);
    }

    /**
     * 当前用户最近一次审核单（待核验页用）。
     *
     * <p>**任何登录用户**都能调：待核验账号需要它读取驳回理由与进度，而这类账号只有 REPORTER 职能。
     * 只返回本人的记录（按 userId 过滤），不存在越权读他人申请的问题。
     */
    @Operation(summary = "查询本人最近一次身份核验申请")
    @GetMapping("/reviews/me/latest")
    @PreAuthorize("isAuthenticated()")
    public R<IdentityReviewVO> myLatestReview(@AuthenticationPrincipal LoginUserPrincipal user) {
        return R.success(reviewService.latestForUser(user.getUserId()));
    }

    @Operation(summary = "分页查询校内人员名单")
    @GetMapping("/roster")
    public R<IPage<IdentityRosterVO>> pageRoster(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status) {
        return R.success(rosterService.page(page, size, keyword, status));
    }

    @Operation(summary = "Excel 导入校内人员名单（按学号/工号幂等）")
    @PostMapping(value = "/roster/import", consumes = "multipart/form-data")
    public R<IdentityImportResultVO> importRoster(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) String batch,
            @AuthenticationPrincipal LoginUserPrincipal user) {
        return R.success("导入完成", rosterService.importRoster(file, user.getUserId(), batch));
    }

    @Operation(summary = "标记名单行在册/离校（不删行）")
    @PutMapping("/roster/{id}/status")
    public R<Void> updateRosterStatus(@PathVariable Long id, @RequestParam Integer status) {
        rosterService.updateStatus(id, status);
        return R.success("状态已更新", null);
    }
}
