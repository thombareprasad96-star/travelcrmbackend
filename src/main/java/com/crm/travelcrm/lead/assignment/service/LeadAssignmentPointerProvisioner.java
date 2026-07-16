package com.crm.travelcrm.lead.assignment.service;

import com.crm.travelcrm.lead.assignment.entity.LeadAssignmentPointer;
import com.crm.travelcrm.lead.assignment.repository.LeadAssignmentPointerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ensures a {@link LeadAssignmentPointer} row exists BEFORE {@code LeadAssignmentService} takes its
 * pessimistic lock. Runs in its OWN transaction ({@link Propagation#REQUIRES_NEW}) so that if two
 * privileged creators race to create the first-ever cursor row for a brand-new tenant, the losing
 * INSERT's constraint violation aborts only this short inner transaction — never the caller's
 * lead-creation transaction. (Cloning a catch-and-reread-in-the-same-transaction pattern would, on
 * PostgreSQL, poison the whole lead-creation txn, because a constraint violation aborts the current
 * transaction until rollback.) A SELECT ... FOR UPDATE cannot lock a not-yet-existent row, which is
 * why the row must be provisioned here first. Mirrors {@code DocumentSequenceProvisioner}.
 */
@Component
@RequiredArgsConstructor
public class LeadAssignmentPointerProvisioner {

    private final LeadAssignmentPointerRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureExists(Long tenantId) {
        if (repository.existsByTenantId(tenantId)) {
            return;
        }
        try {
            repository.saveAndFlush(LeadAssignmentPointer.builder().tenantId(tenantId).build());
        } catch (DataIntegrityViolationException raceLost) {
            // Another request created it first; this inner txn rolls back harmlessly, the row exists.
        }
    }
}