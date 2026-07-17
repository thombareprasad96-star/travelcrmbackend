package com.crm.travelcrm.leadsource.gateway;

import com.crm.travelcrm.leadsource.entity.LeadIngestEvent;
import com.crm.travelcrm.leadsource.entity.LeadIngestStatus;
import com.crm.travelcrm.leadsource.repository.LeadIngestEventRepository;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists the raw-delivery row in its OWN committed transaction.
 *
 * <p><b>Written FIRST, before verification</b> — deliberately inverting the Razorpay precedent, which
 * persists its ledger row LAST and therefore leaves no trace when anything fails. That is exactly why
 * dropped enquiries are invisible today. A rejected delivery must be debuggable, so the row lands
 * before the verdict.
 *
 * <p><b>Why this is its own bean and not a private method on the ingest service.</b> Self-invocation
 * defeats {@code REQUIRES_NEW} entirely — Spring's proxy is bypassed and the "new transaction" quietly
 * joins the caller's, so the row would roll back with the very failure it exists to record. This
 * codebase has already been bitten by that once ({@code PlatformAuditRecorder}). The gateway calls
 * this CROSS-BEAN, and its transaction commits SEQUENTIALLY, before the ingest transaction opens —
 * never nested, because holding two pooled connections at once against a Hikari pool of 10 is its own
 * hazard.
 */
@Service
@RequiredArgsConstructor
public class LeadIngestDeliveryLogger {

    private static final Logger log = LogManager.getLogger(LeadIngestDeliveryLogger.class);

    private final LeadIngestEventRepository repository;

    /**
     * Record an arriving delivery as {@link LeadIngestStatus#RECEIVED} and return its id.
     *
     * <p>Called inside a {@code TenantScope} so that any read ever added here is tenant-filtered. The
     * INSERT itself is safe regardless, because {@code tenantId} is stamped EXPLICITLY rather than
     * read from ambient context: {@code TenantEntityListener} silently accepts an explicit tenantId
     * with a null context (the fourth combination matches no branch), so explicit stamping degrades a
     * mid-method context loss to "row saved correctly" instead of an exception swallowed into an ERROR
     * log.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long logReceived(Long tenantId, Long integrationId, String channel,
                            String externalEventId, String dedupKey,
                            String rawPayload, boolean truncated) {
        LeadIngestEvent event = LeadIngestEvent.builder()
                .integrationId(integrationId)
                .channel(channel)
                .externalEventId(externalEventId)
                .dedupKey(dedupKey)
                .status(LeadIngestStatus.RECEIVED)
                .rawPayload(rawPayload)
                .payloadTruncated(truncated)
                .build();
        event.setTenantId(tenantId);   // EXPLICIT — never ambient. See the javadoc above.
        return repository.save(event).getId();
    }

    /**
     * Move a delivery to its terminal status.
     *
     * <p>{@code REQUIRES_NEW} again, and that is load-bearing: it is how a FAILED status survives the
     * rollback of the transaction that failed. Joining the caller would roll the verdict back together
     * with the thing it is recording, leaving the row stuck at RECEIVED forever.
     *
     * <p>{@code tenantId} is a parameter rather than an ambient read so the lookup can be
     * tenant-scoped. {@code findById} would be both a tenant-isolation ArchUnit violation and, more to
     * the point, genuinely unscoped — and "the id came from our own insert a moment ago" is exactly
     * the reasoning that rots when someone later reuses the method with a request-supplied id.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(Long tenantId, Long eventId, LeadIngestStatus status, Long leadId,
                       String errorMessage) {
        repository.findByIdAndTenantId(eventId, tenantId).ifPresentOrElse(event -> {
            event.setStatus(status);
            event.setLeadId(leadId);
            event.setErrorMessage(truncateError(errorMessage));
            repository.save(event);
        }, () -> log.error("Lead ingest event {} (tenant {}) vanished before its status could be set "
                + "to {}.", eventId, tenantId, status));
    }

    private String truncateError(String message) {
        if (message == null) return null;
        return message.length() <= 1000 ? message : message.substring(0, 997) + "...";
    }
}
