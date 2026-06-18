package com.crm.travelcrm.notification.web;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.notification.domain.entity.Notification;
import com.crm.travelcrm.notification.domain.enums.NotificationStatus;
import com.crm.travelcrm.notification.infrastructure.repository.NotificationRepository;
import com.crm.travelcrm.notification.infrastructure.sse.SseEmitterRegistry;
import com.crm.travelcrm.notification.web.dto.NotificationResponseDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final SseEmitterRegistry     sseEmitterRegistry;

    // ── Feed ──────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationResponseDTO> getNotifications(int page, int size) {
        Long userId  = currentUserId();
        PageRequest pr = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return notificationRepository
                .findAllByRecipientUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId, pr)
                .map(NotificationResponseDTO::from);
    }

    // ── Badge count ───────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public long getUnreadCount() {
        return notificationRepository
                .countByRecipientUserIdAndStatusAndDeletedAtIsNull(
                        currentUserId(), NotificationStatus.UNREAD);
    }

    // ── Mark single read ──────────────────────────────────────────────────────

    @Override
    @Transactional
    public NotificationResponseDTO markRead(UUID publicId) {
        Long userId = currentUserId();
        Notification n = notificationRepository
                .findByPublicIdAndRecipientUserIdAndDeletedAtIsNull(publicId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Notification not found: " + publicId));
        if (n.getStatus() == NotificationStatus.UNREAD) {
            n.markRead();
            notificationRepository.save(n);
            log.debug("Notification {} marked READ for user {}", publicId, userId);
        }
        return NotificationResponseDTO.from(n);
    }

    // ── Mark all read ─────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void markAllRead() {
        Long userId = currentUserId();
        int updated = notificationRepository.markAllReadForUser(userId);
        log.debug("Marked {} notifications READ for user {}", updated, userId);
    }

    // ── SSE subscription ──────────────────────────────────────────────────────

    @Override
    public SseEmitter subscribe() {
        Long userId = currentUserId();
        SseEmitter emitter = sseEmitterRegistry.register(userId);
        log.debug("SSE emitter registered for user {}", userId);
        return emitter;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Extracts the internal Long id of the authenticated user from SecurityContext.
     * The principal is always a {@link User} for tenant requests; SuperAdmin is a
     * separate entity type and does not receive in-app notifications.
     */
    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof User user)) {
            throw new IllegalStateException(
                    "No authenticated User in SecurityContext. " +
                    "Notifications are only available to tenant users, not SuperAdmin.");
        }
        return user.getId();
    }
}