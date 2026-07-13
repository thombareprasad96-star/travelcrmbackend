package com.crm.travelcrm.booking.cancellation.util;

import com.crm.travelcrm.booking.cancellation.entity.DocumentSequence;
import com.crm.travelcrm.booking.cancellation.repository.DocumentSequenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ensures a {@link DocumentSequence} row exists BEFORE the generator takes its pessimistic lock. Runs
 * in its OWN transaction ({@link Propagation#REQUIRES_NEW}) so that if two requests race to create the
 * first number of a series, the losing INSERT's constraint violation aborts only this short inner
 * transaction — never the caller's cancellation transaction. (Cloning the booking generator's
 * catch-and-reread-in-the-same-transaction pattern would, on Postgres, poison the whole cancel txn,
 * because a constraint violation aborts the current transaction until rollback.)
 */
@Component
@RequiredArgsConstructor
public class DocumentSequenceProvisioner {

    private final DocumentSequenceRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureExists(Long tenantId, String seriesCode) {
        if (repository.existsByTenantIdAndSeriesCode(tenantId, seriesCode)) {
            return;
        }
        try {
            repository.saveAndFlush(DocumentSequence.builder()
                    .tenantId(tenantId).seriesCode(seriesCode).lastValue(0L).build());
        } catch (DataIntegrityViolationException raceLost) {
            // Another request created it first; this inner txn rolls back harmlessly, the row exists.
        }
    }
}
