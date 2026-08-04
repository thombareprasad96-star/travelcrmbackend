package com.crm.travelcrm.lead.assignment.history;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the AUTO_ASSIGNED row raised during lead creation, in its <b>own</b> transaction
 * ({@link Propagation#REQUIRES_NEW}) so a history failure can never roll back the lead itself —
 * exactly the contract {@code LeadAssignmentAuditRecorder} already has, and for the same reason.
 *
 * <p>{@link #record} MUST be invoked ACROSS a bean boundary or the {@code REQUIRES_NEW} proxy does
 * not apply and a self-invocation silently joins the caller's transaction. The caller wraps it in
 * try/catch (best-effort).
 *
 * <p><b>The claim/contact/reassign events are deliberately NOT written through here.</b> Those run
 * inside {@code LeadClaimService}'s own transaction, using the repository directly, because there
 * the ownership change and its trail entry are the same act: a claim that succeeded with no history
 * row is a hole in the only record of who took the lead. Lead creation is the opposite case — the
 * lead is the valuable thing and must survive a bookkeeping failure. Two different guarantees,
 * stated in two different places rather than one bean with two meanings.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LeadAssignmentEventRecorder {

    private final LeadAssignmentEventRepository repository;

    /** Persist one history row in a fresh transaction. No-ops when the row has no tenant. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(LeadAssignmentEvent event) {
        if (event == null || event.getTenantId() == null) {
            return;
        }
        repository.save(event);
    }
}
