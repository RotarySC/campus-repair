package com.campus.repair.modules.notification.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sys_notification")
public class SysNotification {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String title;

    private String content;

    /**
     * 通知类型: ORDER_DISPATCH(派单), ORDER_FLOW(进度更新), SLA_ALERT(催办报警), REWORK_ALERT(返工通知), STOCK_WARNING(库存告警), BROADCAST(全校公告)
     */
    private String category;

    private Long relatedOrderId;

    /**
     * 0-未读, 1-已读
     */
    private Integer isRead;

    private LocalDateTime readAt;

    private LocalDateTime createdAt;
}
