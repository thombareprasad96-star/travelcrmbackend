package com.crm.travelcrm.notification.infrastructure.channel;

import com.crm.travelcrm.notification.api.NotificationChannel;
import com.crm.travelcrm.notification.api.NotifyEvent;
import com.crm.travelcrm.notification.domain.entity.Notification;
import com.crm.travelcrm.notification.domain.enums.DeliveryChannel;
import com.crm.travelcrm.notification.infrastructure.repository.NotificationRepository;
import com.crm.travelcrm.notification.infrastructure.sse.SseEmitterRegistry;
import com.crm.travelcrm.notification.web.dto.NotificationResponseDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists the notification to the DB (in-app feed) and immediately pushes
 * it to any live SSE connections for that user.
 *
 * <p>Runs within the same transaction as the event listener so that if the
 * persist fails the event is not silently dropped — the listener can catch
 * and log the error.
 *
 * <p>TenantContext is set by {@code NotifyEventListener} before this method
 * is called, satisfying {@code TenantEntityListener.prePersist()}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InAppNotificationChannel implements NotificationChannel {

    private final NotificationRepository notificationRepository;
    private final SseEmitterRegistry sseEmitterRegistry;

    @Override
    public DeliveryChannel channelType() {
        return DeliveryChannel.IN_APP;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public void send(NotifyEvent event, Notification ignored) {
        for (Long recipientId : event.getRecipientUserIds()) {
            Notification notification = Notification.builder()
                    .recipientUserId(recipientId)
                    .type(event.getType())
                    .title(event.getTitle())
                    .message(event.getMessage())
                    .referenceType(event.getReferenceType())
                    .referencePublicId(event.getReferencePublicId())
                    .build();

            Notification saved = notificationRepository.save(notification);
            log.debug("IN_APP notification {} created for user {}", saved.getPublicId(), recipientId);

            // SSE push — failures here are swallowed; the DB row is already committed
            pushViaSse(recipientId, saved);
        }
    }

    private void pushViaSse(Long userId, Notification n) {
        try {
            NotificationResponseDTO dto = NotificationResponseDTO.from(n);
            sseEmitterRegistry.push(userId, "notification", dto);
        } catch (Exception e) {
            log.warn("SSE push failed for user {} notification {}: {}", userId, n.getPublicId(), e.getMessage());
        }
    }
}