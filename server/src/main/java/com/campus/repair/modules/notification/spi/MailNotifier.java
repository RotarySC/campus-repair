package com.campus.repair.modules.notification.spi;

import com.campus.repair.modules.notification.dto.NotificationPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 邮件通知扩展实现 (可通过 notification.mail.enabled=true 开启)
 */
@Component("mailNotifier")
@ConditionalOnProperty(prefix = "notification.mail", name = "enabled", havingValue = "true")
@Slf4j
public class MailNotifier implements Notifier {

    @Override
    public void sendNotification(NotificationPayload payload) {
        log.info("[MailNotifier] 准备向用户[{}] 邮箱[{}] 发送通知邮件: {}",
                payload.getUserId(), payload.getEmail(), payload.getTitle());
        // 预留通过 JavaMailSender 进行实际邮件投递的逻辑
    }
}
