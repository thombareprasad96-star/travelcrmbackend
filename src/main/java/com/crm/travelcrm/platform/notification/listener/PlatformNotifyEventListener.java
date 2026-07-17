package com.crm.travelcrm.platform.notification.listener;

import com.crm.travelcrm.auth.repository.SuperAdminRepository;
import com.crm.travelcrm.common.entity.SuperAdmin;
import com.crm.travelcrm.platform.notification.api.PlatformNotifyEvent;
import com.crm.travelcrm.platform.notification.dto.PlatformNotificationResponseDTO;
import com.crm.travelcrm.platform.notification.entity.PlatformNotification;
import com.crm.travelcrm.platform.notification.repository.PlatformNotificationRepository;
import com.crm.travelcrm.platform.notification.sse.PlatformSseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * Fans a {@link PlatformNotifyEvent} out to every active SuperAdmin: one persisted row each, plus an
 * immediate push to any live console tab.
 *
 * <p><b>Runs synchronously in the publisher's transaction</b> (plain {@code @EventListener}, like
 * the tenant module's {@code NotifyEventListener}). That is deliberate: if the publisher rolls back —
 * a payment that fails to commit, an upgrade request that doesn't save — the notification rolls back
 * with it. A SuperAdmin should never be told about a row that does not exist.
 *
 * <p><b>No {@code TenantContext} handling</b>, unlike the tenant listener. {@link PlatformNotification}
 * extends {@code BaseEntity}, so {@code TenantEntityListener} never fires for it and there is nothing
 * to stamp. This is the separate realm paying for itself: no ThreadLocal to set, and none to leak.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformNotifyEventListener {

    private final PlatformNotificationRepository platformNotificationRepository;
    private final PlatformSseEmitterRegistry     sseRegistry;
    private final SuperAdminRepository           superAdminRepository;

    @EventListener
    @Transactional(propagation = Propagation.REQUIRED)
    public void onPlatformNotifyEvent(PlatformNotifyEvent event) {
        List<SuperAdmin> recipients = superAdminRepository.findAllByEnabledTrueAndDeletedAtIsNull();
        if (recipients.isEmpty()) {
            log.warn("Platform event {} raised but no active SuperAdmin exists to receive it",
                    event.getType());
            return;
        }

        for (SuperAdmin recipient : recipients) {
            PlatformNotification saved = platformNotificationRepository.save(
                    PlatformNotification.builder()
                            .recipientSuperAdminId(recipient.getId())
                            .type(event.getType())
                            .title(event.getTitle())
                            .message(event.getMessage())
                            .referenceType(event.getReferenceType())
                            .referencePublicId(event.getReferencePublicId())
                            .tenantId(event.getTenantId())
                            .tenantName(event.getTenantName())
                            .tenantPublicId(event.getTenantPublicId())
                            .build());

            log.debug("Platform notification {} ({}) created for super admin {}",
                    saved.getPublicId(), event.getType(), recipient.getId());

            pushAfterCommit(recipient.getId(), saved);
        }
    }

    /**
     * Defers the SSE push until the publisher's transaction commits.
     *
     * <p>The persist above is intentionally inside that transaction so a rollback discards the row.
     * A push is not transactional — once it is on the wire it cannot be recalled — so pushing inline
     * would deliver a live toast for a payment that then rolled back, leaving a notification on the
     * SuperAdmin's screen that exists in no table and vanishes on refresh. Deferring to afterCommit
     * makes both halves agree: no commit, no row AND no push.
     *
     * <p>Falls back to an immediate push when no transaction is active, so a publisher outside a
     * transaction still delivers.
     */
    private void pushAfterCommit(Long superAdminId, PlatformNotification n) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            doPush(superAdminId, n);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                doPush(superAdminId, n);
            }
        });
    }

    /**
     * A failed push must not fail anything — the row is the durable record and the console reads it
     * on next load. After commit there is no transaction left to fail anyway.
     */
    private void doPush(Long superAdminId, PlatformNotification n) {
        try {
            sseRegistry.push(superAdminId, "notification", PlatformNotificationResponseDTO.from(n));
        } catch (Exception e) {
            log.warn("Console SSE push failed for super admin {} notification {}: {}",
                    superAdminId, n.getPublicId(), e.getMessage());
        }
    }
}