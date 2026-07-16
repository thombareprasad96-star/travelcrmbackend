package com.crm.travelcrm.lead.assignment.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes lead-assignment audit rows. The persist runs in its <b>own</b> transaction
 * ({@link Propagation#REQUIRES_NEW}) so the audit write commits independently of — and never rolls
 * back — the lead-creation request that triggered it (same intent as {@code ActivityLogRecorder} /
 * {@code PlatformAuditRecorder}).
 *
 * <p>{@link #record} MUST be invoked ACROSS a bean boundary (from the lead service) so the
 * {@code REQUIRES_NEW} proxy actually applies — a self-invocation would silently run in the caller's
 * transaction and could roll it back. The caller wraps the call in a try/catch so a persistence
 * failure inside the (suspended) inner transaction can never break the lead creation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LeadAssignmentAuditRecorder {

    private final LeadAssignmentAuditRepository repository;

    /**
     * Persist one audit row in a fresh transaction. No-ops when the row has no tenant. Call this
     * across a bean boundary and swallow any thrown exception in the caller (best-effort).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(LeadAssignmentAudit entry) {
        if (entry == null || entry.getTenantId() == null) {
            return;
        }
        repository.save(entry);
    }
}