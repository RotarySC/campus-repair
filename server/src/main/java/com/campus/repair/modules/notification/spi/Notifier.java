package com.campus.repair.modules.notification.spi;

import com.campus.repair.modules.notification.dto.NotificationPayload;

/**
 * 外部通知服务扩展 SPI
 */
public interface Notifier {

    /**
     * 发送通知
     *
     * @param payload 通知载荷
     */
    void sendNotification(NotificationPayload payload);

    /**
     * 该通知通道是否启用
     */
    default boolean isEnabled() {
        return true;
    }
}
