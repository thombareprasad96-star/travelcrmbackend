package com.crm.travelcrm.notification.infrastructure.channel;

import com.crm.travelcrm.notification.api.NotificationChannel;
import com.crm.travelcrm.notification.api.NotifyEvent;
import com.crm.travelcrm.notification.api.TemplateRenderer;
import com.crm.travelcrm.notification.domain.entity.Notification;
import com.crm.travelcrm.notification.domain.enums.DeliveryChannel;
import com.crm.travelcrm.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Sends an email for each recipient in the event.
 *
 * <p><b>Isolation (L principle):</b> runs fully async; failure here never blocks
 * or affects the IN_APP or SSE channels.
 *
 * <p><b>Retry:</b> three attempts with linear back-off (1 s, 2 s, 3 s). After three
 * failures the error is logged and the attempt is abandoned — no dead-letter queue
 * in this implementation (add one if required by SLA).
 *
 * <p><b>Tenancy:</b> {@link UserRepository} queries the {@code users} table which
 * extends {@code BaseEntity} (not {@code BaseTenantEntity}), so no tenant filter
 * or TenantContext is needed here. Email addresses are read by internal user ID.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailNotificationChannel implements NotificationChannel {

    private static final int MAX_ATTEMPTS = 3;

    private final JavaMailSender mailSender;
    private final UserRepository userRepository;
    private final TemplateRenderer templateRenderer;

    @Override
    public DeliveryChannel channelType() {
        return DeliveryChannel.EMAIL;
    }

    @Override
    public void send(NotifyEvent event, Notification notification) {
        // Hand off to async thread immediately — caller thread is never blocked
        sendAsync(event);
    }

    @Async("notificationExecutor")
    public void sendAsync(NotifyEvent event) {
        Map<String, Object> payload = event.getPayload() != null ? event.getPayload() : Map.of();
        String subject = templateRenderer.render(event.getTitle(), payload);
        String body    = templateRenderer.render(event.getMessage() != null ? event.getMessage() : event.getTitle(), payload);

        for (Long recipientId : event.getRecipientUserIds()) {
            userRepository.findById(recipientId).ifPresent(user -> {
                if (user.getEmail() != null) {
                    sendWithRetry(user.getEmail(), subject, body, recipientId);
                }
            });
        }
    }

    private void sendWithRetry(String to, String subject, String body, Long userId) {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                SimpleMailMessage msg = new SimpleMailMessage();
                msg.setTo(to);
                msg.setSubject(subject);
                msg.setText(body);
                mailSender.send(msg);
                log.debug("Email sent to user {} ({})", userId, to);
                return;
            } catch (Exception e) {
                last = e;
                log.warn("Email attempt {}/{} failed for user {}: {}", attempt, MAX_ATTEMPTS, userId, e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    sleepQuietly(1000L * attempt);
                }
            }
        }
        log.error("Email permanently failed for user {} after {} attempts", userId, MAX_ATTEMPTS, last);
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}