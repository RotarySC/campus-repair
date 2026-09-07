package com.campus.repair.modules.notification.spi;

import com.campus.repair.modules.notification.dto.NotificationPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 默认控制台模拟通知器
 */
@Component("mockConsoleNotifier")
@Slf4j
public class MockConsoleNotifier implements Notifier {

    @Override
    public void sendNotification(NotificationPayload payload) {
        log.info("══════════════════════ [MOCK 外部通知通道] ══════════════════════");
        log.info("接收人: {} ({}) | 用户ID: {} | 类别: {}", 
                payload.getRecipientName() != null ? payload.getRecipientName() : "用户", 
                payload.getPhone() != null ? payload.getPhone() : "-", 
                payload.getUserId(),
                payload.getCategory());
        log.info("标题: {}", payload.getTitle());
        log.info("内容: {}", payload.getContent());
        if (payload.getRelatedOrderId() != null) {
            log.info("关联工单ID: {}", payload.getRelatedOrderId());
        }
        log.info("═════════════════════════════════════════════════════════════");
    }
}
