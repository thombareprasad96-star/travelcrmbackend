package com.crm.travelcrm.accounting.invoice.util;

import com.crm.travelcrm.accounting.invoice.entity.InvoiceSequence;
import com.crm.travelcrm.accounting.invoice.enums.InvoiceSeries;
import com.crm.travelcrm.accounting.invoice.repository.InvoiceSequenceRepository;
import com.crm.travelcrm.accounting.support.FinancialYear;
import com.crm.travelcrm.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Mints gap-free per-tenant, per-financial-year GST invoice numbers ({@code TI/2627/00001}). The
 * counter row is pre-provisioned in a separate transaction (see {@link InvoiceSequenceProvisioner});
 * this class then only does a pessimistic-locked read + increment inside the CALLER's transaction, so
 * two concurrent issues for the same tenant/series/FY are serialized on the counter row and can never
 * collide, and a rolled-back issue does not burn a number (gaplessness).
 *
 * <p>Must be called inside the caller's {@code @Transactional} unit so the lock is held to commit.
 */
@Component
@RequiredArgsConstructor
public class InvoiceNumberGenerator {

    /** GST Rule 46(b): the invoice serial may not exceed 16 characters. */
    private static final int GST_MAX_LENGTH = 16;

    private final InvoiceSequenceRepository repository;
    private final InvoiceSequenceProvisioner provisioner;

    public String next(Long tenantId, InvoiceSeries series, FinancialYear fy) {
        String code = series.seriesCode();
        String finYear = fy.label();
        provisioner.ensureExists(tenantId, code, finYear);   // committed in its own txn before we lock

        InvoiceSequence seq = repository
                .findByTenantIdAndSeriesCodeAndFinancialYear(tenantId, code, finYear)
                .orElseThrow(() -> new IllegalStateException(
                        "Invoice sequence row missing after provisioning: " + code + "/" + finYear));
        long next = seq.getLastValue() + 1;
        seq.setLastValue(next);
        repository.saveAndFlush(seq);

        String number = String.format("%s/%s/%05d", code, fy.compact(), next);
        if (number.length() > GST_MAX_LENGTH) {
            // A tenant crossing 99,999 invoices in one FY (6+ digits) breaches the statutory cap.
            throw new BusinessException(
                    "Invoice number '" + number + "' exceeds the GST 16-character limit for this financial year.",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return number;
    }
}