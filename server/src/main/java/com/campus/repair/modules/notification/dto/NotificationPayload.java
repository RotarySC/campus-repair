package com.campus.repair.modules.notification.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationPayload {

    private Long userId;

    private String recipientName;

    private String phone;

    private String email;

    private String title;

    private String content;

    private String category;

    private Long relatedOrderId;
}
