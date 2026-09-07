package com.campus.repair.modules.notification.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.repair.common.result.R;
import com.campus.repair.framework.security.LoginUserPrincipal;
import com.campus.repair.modules.notification.dto.NotificationQueryRequest;
import com.campus.repair.modules.notification.service.NotificationService;
import com.campus.repair.modules.notification.vo.NotificationVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "站内消息与提醒通知中心", description = "用户个人消息列表、未读徽标与已读标记")
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

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
}
