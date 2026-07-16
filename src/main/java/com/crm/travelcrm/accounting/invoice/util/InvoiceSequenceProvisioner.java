package com.crm.travelcrm.accounting.invoice.util;

import com.crm.travelcrm.accounting.invoice.entity.InvoiceSequence;
import com.crm.travelcrm.accounting.invoice.repository.InvoiceSequenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ensures the {@link InvoiceSequence} row for a (tenant, series, financial-year) exists BEFORE the
 * generator takes its pessimistic lock. Runs in its OWN transaction ({@link Propagation#REQUIRES_NEW})
 * so that if two requests race to mint the first number of a series, the losing INSERT's constraint
 * violation aborts only this short inner transaction — never the caller's invoice-issue transaction.
 * (On Postgres, catching the violation in the caller's own transaction would poison it until rollback.)
 */
@Component
@RequiredArgsConstructor
public class InvoiceSequenceProvisioner {

    private final InvoiceSequenceRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureExists(Long tenantId, String seriesCode, String financialYear) {
        if (repository.existsByTenantIdAndSeriesCodeAndFinancialYear(tenantId, seriesCode, financialYear)) {
            return;
        }
        try {
            repository.saveAndFlush(InvoiceSequence.builder()
                    .tenantId(tenantId).seriesCode(seriesCode).financialYear(financialYear)
                    .lastValue(0L).build());
        } catch (DataIntegrityViolationException raceLost) {
            // Another request created it first; this inner txn rolls back harmlessly, the row exists.
        }
    }
}