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

    private Integer isRead;

    private LocalDateTime readAt;

    private LocalDateTime createdAt;
}
