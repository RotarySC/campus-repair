package com.campus.repair.modules.notification.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationVO {

    private Long id;

    private Long userId;

    private String title;

    private String content;

    private String category;

    private Long relatedOrderId;

    /** 公告类通知回指的公告 id（非 BROADCAST 为 null）；前端据此跳公告管理页。 */
    private Long announcementId;

    private Integer isRead;

    private LocalDateTime readAt;

    private LocalDateTime createdAt;
}
