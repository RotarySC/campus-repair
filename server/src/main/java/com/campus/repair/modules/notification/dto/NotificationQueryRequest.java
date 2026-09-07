package com.campus.repair.modules.notification.dto;

import lombok.Data;

@Data
public class NotificationQueryRequest {

    private Integer current = 1;

    private Integer size = 20;

    /**
     * 过滤状态: null 或 -1 为全部, 0-仅未读, 1-仅已读
     */
    private Integer isRead;

    /**
     * 分类过滤
     */
    private String category;
}
