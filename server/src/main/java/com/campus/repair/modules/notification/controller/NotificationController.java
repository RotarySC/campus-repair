package com.campus.repair.modules.notification.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.repair.common.result.R;
import com.campus.repair.framework.security.LoginUserPrincipal;
import com.campus.repair.modules.notification.dto.NotificationQueryRequest;
import com.campus.repair.modules.notification.service.NotificationService;
import com.campus.repair.modules.notification.vo.NotificationVO;
import com.campus.repair.modules.review.service.AnnouncementService;
import com.campus.repair.modules.review.vo.AnnouncementDraftVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "站内消息与提醒通知中心", description = "用户个人消息列表、未读徽标与已读标记")
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final AnnouncementService announcementService;

    @Operation(summary = "分页获取当前登录用户的站内通知列表")
    @GetMapping("/my")
    public R<Page<NotificationVO>> getMyNotifications(
            @AuthenticationPrincipal LoginUserPrincipal user,
            NotificationQueryRequest request) {
        return R.success(notificationService.getUserNotifications(user.getUserId(), request));
    }

    @Operation(summary = "获取当前用户的未读消息数")
    @GetMapping("/unread-count")
    public R<Long> getUnreadCount(@AuthenticationPrincipal LoginUserPrincipal user) {
        return R.success(notificationService.getUnreadCount(user.getUserId()));
    }

    @Operation(summary = "标记单条消息为已读")
    @PutMapping("/{id}/read")
    public R<Boolean> markAsRead(
            @PathVariable Long id,
            @AuthenticationPrincipal LoginUserPrincipal user) {
        return R.success(notificationService.markAsRead(user.getUserId(), id));
    }

    @Operation(summary = "一键标记当前用户全部消息为已读")
    @PutMapping("/read-all")
    public R<Integer> markAllAsRead(@AuthenticationPrincipal LoginUserPrincipal user) {
        return R.success(notificationService.markAllAsRead(user.getUserId()));
    }

    /**
     * 旧端点兼容：语义已从「立即向全校扇出」改为「创建公告草稿」。
     *
     * <p>路径与权限保持不变，前端旧入口不会 404；但「先审后发」不可绕过——
     * 本端点**不产生任何 {@code sys_notification}**，只返回 {@code announcementId}，
     * 调用方应引导到公告工作台继续提交审核/审核/发布。
     */
    @Operation(summary = "发布全校后勤广播公告 (兼容旧入口：仅创建公告草稿，不扇出)")
    @PostMapping("/broadcast")
    @PreAuthorize("hasAnyRole('PLATFORM_ADMIN', 'DISPATCHER')")
    public R<AnnouncementDraftVO> broadcastAnnouncement(
            @jakarta.validation.Valid @RequestBody com.campus.repair.modules.notification.dto.BroadcastNotificationRequest request,
            @AuthenticationPrincipal LoginUserPrincipal user) {
        AnnouncementDraftVO draft = new AnnouncementDraftVO(
                announcementService.createDraft(request.getTitle(), request.getContent(),
                        user.getUserId(), user.getUsername()).getId());
        return R.success("已创建公告草稿，请到公告工作台提交审核后发布", draft);
    }
}
