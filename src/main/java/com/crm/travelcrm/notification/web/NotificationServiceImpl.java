package com.crm.travelcrm.notification.web;

import com.crm.travelcrm.auth.api.CurrentUserProvider;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.BusinessException;
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
import org.springframework.http.HttpStatus;
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
    private final CurrentUserProvider    currentUserProvider;

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

    // ── Soft delete ───────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void delete(UUID publicId) {
        Long userId = currentUserId();
        Notification n = notificationRepository
                .findByPublicIdAndRecipientUserIdAndDeletedAtIsNull(publicId, userId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Notification not found: " + publicId));
        n.softDelete(currentUserProvider.currentUsernameOrSystem());
        notificationRepository.save(n);
        log.debug("Notification {} soft-deleted for user {}", publicId, userId);
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
        // Tenant comes from TenantContext, populated moments ago by TokenAuthenticator when it
        // validated the query-param token — the same claim JwtAuthFilter would have used. It is what
        // makes this connection eligible for tenant broadcasts (the new-lead alert); a null tenant
        // simply receives none rather than being filed under a default and seeing everyone's.
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null) {
            log.warn("SSE subscription for user {} has no tenant in context — this connection will "
                    + "receive personal notifications but no tenant broadcasts.", userId);
        }
        SseEmitter emitter = sseEmitterRegistry.register(tenantId, userId);
        log.debug("SSE emitter registered for user {} (tenant {})", userId, tenantId);
        return emitter;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Internal Long id of the authenticated tenant user. A SuperAdmin is a separate entity type
     * with its own feed under {@code /api/super-admin/notifications}, so it has no place here.
     *
     * <p>This is a 403, not an {@code IllegalStateException}: a platform session reaching this
     * method is a routing mistake the caller can act on, not an impossible state. The old
     * {@code IllegalStateException} had no handler and fell through to the catch-all as a 500.
     */
    private Long currentUserId() {
        Long userId = currentUserProvider.currentUserIdOrNull();
        if (userId == null) {
            throw new BusinessException(
                    "Notifications are not available for platform sessions.",
                    HttpStatus.FORBIDDEN);
        }
        return userId;
    }
}