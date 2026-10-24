package com.campus.repair.modules.review.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.campus.repair.common.result.R;
import com.campus.repair.framework.security.LoginUserPrincipal;
import com.campus.repair.modules.review.dto.AnnouncementCreateRequest;
import com.campus.repair.modules.review.dto.AnnouncementReviewRequest;
import com.campus.repair.modules.review.dto.AnnouncementUpdateRequest;
import com.campus.repair.modules.review.service.AnnouncementService;
import com.campus.repair.modules.review.vo.AnnouncementVO;
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

/**
 * 维修公告的「先审后发」工作台。
 *
 * <p>权限：后勤公告由后勤发，整类收在 {@code DISPATCHER / PLATFORM_ADMIN}。
 * 允许自审（单调度员演示场景必需），审核人照样落库；生产双人复核可通过增加「审核人≠作者」校验实现。
 */
@Tag(name = "维修公告", description = "公告草稿 → 提交审核 → 审核 → 发布的先审后发工作台")
@RestController
@RequestMapping("/api/v1/announcements")
@PreAuthorize("hasAnyRole('DISPATCHER', 'PLATFORM_ADMIN')")
@RequiredArgsConstructor
public class AnnouncementController {

    private final AnnouncementService announcementService;

    @Operation(summary = "分页查询公告工作台（status 省略=全部）")
    @GetMapping
    public R<IPage<AnnouncementVO>> page(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        return R.success(announcementService.page(page, size, status, keyword));
    }

    @Operation(summary = "公告详情")
    @GetMapping("/{id}")
    public R<AnnouncementVO> detail(@PathVariable Long id) {
        return R.success(announcementService.get(id));
    }

    @Operation(summary = "新建公告草稿（不扇出）")
    @PostMapping
    public R<AnnouncementVO> create(@Valid @RequestBody AnnouncementCreateRequest request,
                                    @AuthenticationPrincipal LoginUserPrincipal user) {
        return R.success("公告草稿已创建",
                announcementService.createDraft(request.getTitle(), request.getContent(),
                        user.getUserId(), user.getUsername()));
    }

    @Operation(summary = "编辑公告（仅草稿/已驳回）")
    @PutMapping("/{id}")
    public R<AnnouncementVO> update(@PathVariable Long id,
                                    @Valid @RequestBody AnnouncementUpdateRequest request) {
        return R.success("公告已保存",
                announcementService.update(id, request.getTitle(), request.getContent()));
    }

    @Operation(summary = "提交审核（DRAFT|REJECTED → PENDING_REVIEW，同时落内容审核轨迹）")
    @PostMapping("/{id}/submit")
    public R<AnnouncementVO> submit(@PathVariable Long id) {
        return R.success("已提交审核", announcementService.submit(id));
    }

    @Operation(summary = "审核公告（APPROVED / REJECTED，驳回必填理由）")
    @PostMapping("/{id}/review")
    public R<AnnouncementVO> review(@PathVariable Long id,
                                    @Valid @RequestBody AnnouncementReviewRequest request,
                                    @AuthenticationPrincipal LoginUserPrincipal user) {
        return R.success("审核完成",
                announcementService.review(id, request.getDecision(), request.getReason(),
                        user.getUserId(), user.getUsername()));
    }

    @Operation(summary = "发布公告（仅 APPROVED；发布时才扇出全校站内信）")
    @PostMapping("/{id}/publish")
    public R<AnnouncementVO> publish(@PathVariable Long id) {
        return R.success("公告已发布", announcementService.publish(id));
    }
}
