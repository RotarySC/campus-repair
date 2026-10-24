package com.campus.repair.modules.notification.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campus.repair.modules.notification.dto.NotificationPayload;
import com.campus.repair.modules.notification.dto.NotificationQueryRequest;
import com.campus.repair.modules.notification.entity.SysNotification;
import com.campus.repair.modules.notification.mapper.SysNotificationMapper;
import com.campus.repair.modules.notification.spi.Notifier;
import com.campus.repair.modules.notification.vo.NotificationVO;
import com.campus.repair.modules.user.entity.SysUser;
import com.campus.repair.modules.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final SysNotificationMapper notificationMapper;
    private final List<Notifier> notifiers;
    private final UserService userService;

    /**
     * 发送站内信并触发外部通道通知 (异步保存与分发)
     */
    @Async
    public void sendNotificationAsync(Long userId, String title, String content, String category, Long relatedOrderId) {
        sendNotification(userId, title, content, category, relatedOrderId);
    }

    /**
     * 发送通知核心方法 (同步/内嵌)
     */
    @Transactional(rollbackFor = Exception.class)
    public SysNotification sendNotification(Long userId, String title, String content, String category, Long relatedOrderId) {
        SysNotification notification = new SysNotification();
        notification.setUserId(userId);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setCategory(category != null ? category : "ORDER_FLOW");
        notification.setRelatedOrderId(relatedOrderId);
        notification.setIsRead(0);
        notification.setCreatedAt(LocalDateTime.now());
        notificationMapper.insert(notification);

        // 构造 Payload 并调用外部 Notifier SPI
        NotificationPayload payload = NotificationPayload.builder()
                .userId(userId)
                .title(title)
                .content(content)
                .category(category)
                .relatedOrderId(relatedOrderId)
                .build();

        try {
            SysUser recipient = userService.getUserById(userId);
            if (recipient != null) {
                payload.setRecipientName(recipient.getRealName());
                payload.setPhone(recipient.getPhone());
                // 如果用户有 department 或扩展邮箱
            }
        } catch (Exception e) {
            log.warn("[NotificationService] 查询接收人信息失败, 不影响通知入库: {}", e.getMessage());
        }

        if (notifiers != null && !notifiers.isEmpty()) {
            for (Notifier notifier : notifiers) {
                if (notifier.isEnabled()) {
                    try {
                        notifier.sendNotification(payload);
                    } catch (Exception e) {
                        log.error("[NotificationService] 通知器[{}] 投递失败: {}", notifier.getClass().getSimpleName(), e.getMessage());
                    }
                }
            }
        }

        return notification;
    }

    /**
     * 分页查询当前用户的站内信列表
     */
    public Page<NotificationVO> getUserNotifications(Long userId, NotificationQueryRequest request) {
        Page<SysNotification> page = new Page<>(request.getCurrent(), request.getSize());
        LambdaQueryWrapper<SysNotification> wrapper = new LambdaQueryWrapper<SysNotification>()
                .eq(SysNotification::getUserId, userId);

        if (request.getIsRead() != null && request.getIsRead() >= 0) {
            wrapper.eq(SysNotification::getIsRead, request.getIsRead());
        }
        if (request.getCategory() != null && !request.getCategory().isBlank()) {
            wrapper.eq(SysNotification::getCategory, request.getCategory().trim());
        }

        wrapper.orderByDesc(SysNotification::getCreatedAt);
        Page<SysNotification> result = notificationMapper.selectPage(page, wrapper);

        List<NotificationVO> voList = result.getRecords().stream().map(entity -> {
            NotificationVO vo = new NotificationVO();
            BeanUtils.copyProperties(entity, vo);
            return vo;
        }).collect(Collectors.toList());

        Page<NotificationVO> voPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        voPage.setRecords(voList);
        return voPage;
    }

    /**
     * 获取用户未读通知数
     */
    public long getUnreadCount(Long userId) {
        return notificationMapper.selectCount(new LambdaQueryWrapper<SysNotification>()
                .eq(SysNotification::getUserId, userId)
                .eq(SysNotification::getIsRead, 0));
    }

    /**
     * 标记单条通知为已读 (仅限当前用户本人)
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean markAsRead(Long userId, Long notificationId) {
        return notificationMapper.update(null, new LambdaUpdateWrapper<SysNotification>()
                .set(SysNotification::getIsRead, 1)
                .set(SysNotification::getReadAt, LocalDateTime.now())
                .eq(SysNotification::getId, notificationId)
                .eq(SysNotification::getUserId, userId)) > 0;
    }

    /**
     * 一键将当前用户的所有未读通知标记为已读
     */
    @Transactional(rollbackFor = Exception.class)
    public int markAllAsRead(Long userId) {
        return notificationMapper.update(null, new LambdaUpdateWrapper<SysNotification>()
                .set(SysNotification::getIsRead, 1)
                .set(SysNotification::getReadAt, LocalDateTime.now())
                .eq(SysNotification::getUserId, userId)
                .eq(SysNotification::getIsRead, 0));
    }

    /**
     * 向全校在册正常用户群体发布后勤广播公告 (Fan-out 扇出)。
     *
     * <p>旧签名保留并委托给带 {@code announcementId} 的重载（传 null）：历史上只按
     * 「标题 + 正文」扇出的调用方不需要改，但**新链路（公告先审后发）必须走 3 参版本**，
     * 让每条站内信都能回指 {@code sys_announcement.id}。
     */
    @Transactional(rollbackFor = Exception.class)
    public int broadcastAnnouncement(String title, String content) {
        return broadcastAnnouncement(title, content, null);
    }

    /**
     * 公告发布扇出：向全校在册正常用户各写一条 BROADCAST 站内信，并回写公告 id。
     *
     * <p>只应由 {@code AnnouncementService#publish} 在公告状态迁移到 {@code PUBLISHED} 时调用——
     * 任何未发布状态都不产生站内信，这是「先审后发」不可绕过的落点。
     *
     * @param announcementId 公告本体 id；null 表示不关联公告（兼容旧调用）
     * @return 实际扇出的条数（= 正常账号数）
     */
    @Transactional(rollbackFor = Exception.class)
    public int broadcastAnnouncement(String title, String content, Long announcementId) {
        List<SysUser> activeUsers = userService.getAllActiveUsers();
        if (activeUsers == null || activeUsers.isEmpty()) {
            return 0;
        }

        LocalDateTime now = LocalDateTime.now();
        int count = 0;
        for (SysUser user : activeUsers) {
            SysNotification notification = new SysNotification();
            notification.setUserId(user.getId());
            notification.setTitle(title);
            notification.setContent(content);
            notification.setCategory("BROADCAST");
            notification.setAnnouncementId(announcementId);
            notification.setIsRead(0);
            notification.setCreatedAt(now);
            notificationMapper.insert(notification);
            count++;
        }

        log.info("[NotificationService] 成功向 {} 位系统活跃用户扇出发布全校后勤广播公告: {} (announcementId={})",
                count, title, announcementId);
        return count;
    }
}
