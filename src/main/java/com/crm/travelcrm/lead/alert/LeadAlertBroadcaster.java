package com.crm.travelcrm.lead.alert;

import com.crm.travelcrm.lead.alert.dto.LeadAlertDto;
import com.crm.travelcrm.notification.infrastructure.sse.SseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Pushes claim-window events to every connected user of a tenant.
 *
 * <p><b>Always after commit.</b> An SSE push is not transactional and cannot be taken back: fire it
 * inside the transaction and a subsequent rollback leaves every browser in the tenant showing a
 * lead that does not exist, with a Claim button that 404s. {@code LeadIngestGateway} already
 * defers its notifications for exactly this reason; this follows the same rule rather than
 * inventing a second one.
 *
 * <p><b>The payload must be built by the CALLER, inside the transaction.</b> This class takes a
 * finished {@link LeadAlertDto}, never a {@code Lead}. By the time the callback runs the Hibernate
 * session is closed, so touching a lazy association there — the itinerary, the assigned user —
 * throws {@code LazyInitializationException} from a thread nobody is watching, and the alert simply
 * never arrives. Snapshotting first makes that mistake impossible to write.
 *
 * <p>Failures are swallowed. A lead that was created successfully must not be rolled back, or
 * reported as failed, because a websocket-ish push did not land; the persisted in-app notification
 * and the alert feed are the durable paths.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LeadAlertBroadcaster {

    private final SseEmitterRegistry sseEmitterRegistry;

    /** A new lead is open to claim — the broadcast the whole feature is named for. */
    public void broadcastNewLead(Long tenantId, LeadAlertDto alert) {
        afterCommit(tenantId, LeadAlertEvent.NEW_LEAD, alert);
    }

    /** Ownership moved while the lead was still open. Carries the bumped version. */
    public void broadcastClaimed(Long tenantId, LeadAlertDto alert) {
        afterCommit(tenantId, LeadAlertEvent.CLAIMED, alert);
    }

    /** First contact made, or the window reopened — either way the claimable state changed. */
    public void broadcastLockChanged(Long tenantId, LeadAlertDto alert) {
        afterCommit(tenantId, LeadAlertEvent.LOCKED, alert);
    }

    /**
     * Register the push to run once the current transaction commits, or send immediately when there
     * is no transaction to wait for (a caller outside one has nothing that could roll back).
     */
    private void afterCommit(Long tenantId, String eventName, LeadAlertDto alert) {
        if (tenantId == null || alert == null) {
            log.warn("Lead alert '{}' dropped: tenantId or payload was null.", eventName);
            return;
        }

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            send(tenantId, eventName, alert);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                send(tenantId, eventName, alert);
            }
        });
    }

    private void send(Long tenantId, String eventName, LeadAlertDto alert) {
        try {
            int delivered = sseEmitterRegistry.pushToTenant(tenantId, eventName, alert);
            log.debug("Lead alert '{}' for {} delivered to {} connection(s) in tenant {}",
                    eventName, alert.getLeadPublicId(), delivered, tenantId);
        } catch (Exception e) {
            // Never rethrow: afterCommit runs on the committing thread, and an exception here would
            // surface as a failure of an operation that has already succeeded and cannot be undone.
            log.warn("Lead alert '{}' for {} failed to broadcast in tenant {}: {}",
                    eventName, alert.getLeadPublicId(), tenantId, e.getMessage());
        }
    }
}
