package com.crm.travelcrm.platform.notification.service;

import com.crm.travelcrm.common.entity.SuperAdmin;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.exception.ResourceNotFoundException;
import com.crm.travelcrm.platform.notification.dto.PlatformNotificationResponseDTO;
import com.crm.travelcrm.platform.notification.entity.PlatformNotification;
import com.crm.travelcrm.platform.notification.enums.PlatformNotificationStatus;
import com.crm.travelcrm.platform.notification.repository.PlatformNotificationRepository;
import com.crm.travelcrm.platform.notification.sse.PlatformSseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformNotificationServiceImpl implements PlatformNotificationService {

    private final PlatformNotificationRepository platformNotificationRepository;
    private final PlatformSseEmitterRegistry     sseRegistry;

    // ── Feed ──────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Page<PlatformNotificationResponseDTO> getNotifications(int page, int size) {
        PageRequest pr = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return platformNotificationRepository
                .findAllByRecipientSuperAdminIdAndDeletedAtIsNullOrderByCreatedAtDesc(
                        currentSuperAdminId(), pr)
                .map(PlatformNotificationResponseDTO::from);
    }

    // ── Badge count ───────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public long getUnreadCount() {
        return platformNotificationRepository
                .countByRecipientSuperAdminIdAndStatusAndDeletedAtIsNull(
                        currentSuperAdminId(), PlatformNotificationStatus.UNREAD);
    }

    // ── Mark single read ──────────────────────────────────────────────────────

    @Override
    @Transactional
    public PlatformNotificationResponseDTO markRead(UUID publicId) {
        Long superAdminId = currentSuperAdminId();
        PlatformNotification n = requireOwned(publicId, superAdminId);
        if (n.getStatus() == PlatformNotificationStatus.UNREAD) {
            n.markRead();
            platformNotificationRepository.save(n);
            log.debug("Platform notification {} marked READ for super admin {}", publicId, superAdminId);
        }
        return PlatformNotificationResponseDTO.from(n);
    }

    // ── Mark all read ─────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void markAllRead() {
        Long superAdminId = currentSuperAdminId();
        int updated = platformNotificationRepository.markAllReadForSuperAdmin(superAdminId);
        log.debug("Marked {} platform notifications READ for super admin {}", updated, superAdminId);
    }

    // ── Soft delete ───────────────────────────────────────────────────────────

    @Override
    @Transactional
    public void delete(UUID publicId) {
        Long superAdminId = currentSuperAdminId();
        PlatformNotification n = requireOwned(publicId, superAdminId);
        n.softDelete(currentSuperAdmin().getEmail());
        platformNotificationRepository.save(n);
        log.debug("Platform notification {} soft-deleted for super admin {}", publicId, superAdminId);
    }

    // ── SSE subscription ──────────────────────────────────────────────────────

    @Override
    public SseEmitter subscribe() {
        Long superAdminId = currentSuperAdminId();
        SseEmitter emitter = sseRegistry.register(superAdminId);
        log.debug("Console SSE emitter registered for super admin {}", superAdminId);
        return emitter;
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** A foreign publicId is a 404, never another SuperAdmin's row. */
    private PlatformNotification requireOwned(UUID publicId, Long superAdminId) {
        return platformNotificationRepository
                .findByPublicIdAndRecipientSuperAdminIdAndDeletedAtIsNull(publicId, superAdminId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Notification not found: " + publicId));
    }

    private Long currentSuperAdminId() {
        return currentSuperAdmin().getId();
    }

    /**
     * The authenticated SuperAdmin, read straight off the principal — the same shape
     * {@code SuperAdminMeController} uses.
     *
     * <p>Deliberately NOT {@code PlatformContext}: that is populated by {@code JwtAuthFilter}, which
     * never runs for the header-less SSE flow ({@code EventSource} cannot send an Authorization
     * header, so the token arrives as a query param and is validated in the controller). The
     * principal is set on both paths; PlatformContext is not.
     *
     * <p>A 403 rather than an {@code IllegalStateException} — the same lesson the tenant module's
     * feed just learned: a wrong-realm session is a routing mistake with an answer, not an
     * impossible state, and an unhandled exception here becomes a 500.
     */
    private SuperAdmin currentSuperAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object principal = auth != null ? auth.getPrincipal() : null;
        if (!(principal instanceof SuperAdmin superAdmin)) {
            throw new BusinessException("Not a platform session.", HttpStatus.FORBIDDEN);
        }
        return superAdmin;
    }
}