package com.crm.travelcrm.quotation.listener;

import com.crm.travelcrm.common.event.LeadRestoredEvent;
import com.crm.travelcrm.common.event.LeadSoftDeletedEvent;
import com.crm.travelcrm.quotation.repository.QuotationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Cascades a lead soft-delete onto its quotations. Decoupled from the lead module —
 * the only shared type is {@link LeadSoftDeletedEvent} (in {@code common}).
 *
 * <p>The handler is a synchronous {@code @EventListener} marked {@code @Transactional},
 * so when the lead service publishes the event from inside its own transaction this
 * runs in (joins) that same transaction: the quotations and the lead are soft-deleted
 * atomically — if either fails, both roll back.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class QuotationEventListener {

    private final QuotationRepository quotationRepository;

    @EventListener
    @Transactional
    public void onLeadSoftDeleted(LeadSoftDeletedEvent event) {
        int affected = quotationRepository.softDeleteByLeadId(
                event.leadId(),
                event.tenantId(),
                LocalDateTime.now(),
                currentUserEmail());

        log.info("Cascade soft-deleted {} quotation(s) for lead id {} | tenantId: {}",
                affected, event.leadId(), event.tenantId());
    }

    /**
     * Cascades a lead restore onto its quotations — symmetric with {@link #onLeadSoftDeleted}.
     * Restoring a lead from Trash brings every quotation that was trashed with it back into the
     * lead's normal views. Predictable and recoverable: a lead and its quotations move through
     * Trash together; anything mistakenly co-restored is still independently re-trashable.
     */
    @EventListener
    @Transactional
    public void onLeadRestored(LeadRestoredEvent event) {
        int affected = quotationRepository.restoreByLeadId(event.leadId(), event.tenantId());
        log.info("Cascade restored {} quotation(s) for lead id {} | tenantId: {}",
                affected, event.leadId(), event.tenantId());
    }

    private String currentUserEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }
}