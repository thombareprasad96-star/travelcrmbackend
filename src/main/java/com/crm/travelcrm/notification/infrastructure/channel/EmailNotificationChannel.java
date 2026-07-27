package com.crm.travelcrm.notification.infrastructure.channel;

import com.crm.travelcrm.auth.api.UserDirectory;
import com.crm.travelcrm.notification.api.NotificationChannel;
import com.crm.travelcrm.notification.api.NotifyEvent;
import com.crm.travelcrm.notification.api.TemplateRenderer;
import com.crm.travelcrm.notification.domain.entity.Notification;
import com.crm.travelcrm.notification.domain.enums.DeliveryChannel;
import com.crm.travelcrm.settings.service.TenantMailSenderFactory;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Sends tenant-scoped email notifications only through the tenant's configured SMTP account.
 * Missing tenant SMTP skips email delivery; in-app and SSE channels are unaffected.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailNotificationChannel implements NotificationChannel {

    private static final int MAX_ATTEMPTS = 3;

    private final TenantMailSenderFactory mailSenderFactory;
    private final UserDirectory userDirectory;
    private final TemplateRenderer templateRenderer;

    @Override
    public DeliveryChannel channelType() {
        return DeliveryChannel.EMAIL;
    }

    @Override
    public void send(NotifyEvent event, Notification notification) {
        sendAsync(event);
    }

    @Async("notificationExecutor")
    public void sendAsync(NotifyEvent event) {
        TenantMailSenderFactory.ResolvedMail mail;
        try {
            mail = mailSenderFactory.resolve(event.getTenantId());
        } catch (Exception e) {
            log.debug("Tenant email notification skipped for tenant {}: {}", event.getTenantId(), e.getMessage());
            return;
        }

        Map<String, Object> payload = event.getPayload() != null ? event.getPayload() : Map.of();
        String subject = templateRenderer.render(event.getTitle(), payload);
        String body = templateRenderer.render(
                event.getMessage() != null ? event.getMessage() : event.getTitle(), payload);

        for (Long recipientId : event.getRecipientUserIds()) {
            userDirectory.emailById(recipientId)
                    .ifPresent(email -> sendWithRetry(mail, email, subject, body, recipientId));
        }
    }

    private void sendWithRetry(TenantMailSenderFactory.ResolvedMail mail,
                               String to, String subject, String body, Long userId) {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                MimeMessage msg = mail.sender().createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(msg, false, StandardCharsets.UTF_8.name());
                if (StringUtils.hasText(mail.fromName())) {
                    helper.setFrom(mail.from(), mail.fromName());
                } else {
                    helper.setFrom(mail.from());
                }
                helper.setTo(to);
                helper.setSubject(subject);
                helper.setText(body, false);
                mail.sender().send(msg);
                log.debug("Tenant email sent to user {} ({})", userId, to);
                return;
            } catch (Exception e) {
                last = e;
                log.warn("Tenant email attempt {}/{} failed for user {}: {}",
                        attempt, MAX_ATTEMPTS, userId, e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    sleepQuietly(1000L * attempt);
                }
            }
        }
        log.error("Tenant email permanently failed for user {} after {} attempts", userId, MAX_ATTEMPTS, last);
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
