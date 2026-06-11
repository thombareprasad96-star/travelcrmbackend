package com.crm.travelcrm.notification.infrastructure.channel;

import com.crm.travelcrm.notification.api.NotificationChannel;
import com.crm.travelcrm.notification.api.NotifyEvent;
import com.crm.travelcrm.notification.domain.entity.Notification;
import com.crm.travelcrm.notification.domain.enums.DeliveryChannel;
import com.crm.travelcrm.notification.infrastructure.sse.SseEmitterRegistry;
import com.crm.travelcrm.notification.web.dto.NotificationResponseDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Standalone SSE channel — used when a caller explicitly includes {@code DeliveryChannel.SSE}
 * without {@code IN_APP} (rare but supported for ephemeral push-only messages).
 *
 * <p>When {@code IN_APP} is also requested, {@link InAppNotificationChannel} already
 * triggers the SSE push inline — this channel is a no-op in that case to avoid
 * duplicate pushes. The dispatcher calls channels in order, so IN_APP always runs first.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SseNotificationChannel implements NotificationChannel {

    private final SseEmitterRegistry registry;

    @Override
    public DeliveryChannel channelType() {
        return DeliveryChannel.SSE;
    }

    @Override
    public void send(NotifyEvent event, Notification notification) {
        if (notification != null) {
            // IN_APP channel already pushed; avoid duplicate
            return;
        }
        // SSE-only ephemeral push (no DB row)
        for (Long recipientId : event.getRecipientUserIds()) {
            try {
                NotificationResponseDTO dto = NotificationResponseDTO.ephemeral(event);
                registry.push(recipientId, "notification", dto);
                log.debug("SSE-only push to user {} type={}", recipientId, event.getType());
            } catch (Exception e) {
                log.warn("SSE-only push failed for user {}: {}", recipientId, e.getMessage());
            }
        }
    }
}