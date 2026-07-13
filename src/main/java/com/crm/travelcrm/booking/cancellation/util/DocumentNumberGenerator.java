package com.crm.travelcrm.booking.cancellation.util;

import com.crm.travelcrm.booking.cancellation.entity.DocumentSequence;
import com.crm.travelcrm.booking.cancellation.enums.DocumentType;
import com.crm.travelcrm.booking.cancellation.repository.DocumentSequenceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Year;

/**
 * Mints gap-free per-tenant document numbers (CN-YY-NNNN, DBN-YY-NNNN, RFV-YY-NNNN). The row is
 * pre-provisioned in a separate transaction (see {@link DocumentSequenceProvisioner}); this class then
 * only does a pessimistic-locked read + increment inside the caller's transaction, so two concurrent
 * cancellations for the same tenant are serialized on the counter row and can never collide.
 *
 * <p>Must be called inside the caller's {@code @Transactional} unit so the lock is held to commit.
 */
@Component
@RequiredArgsConstructor
public class DocumentNumberGenerator {

    private final DocumentSequenceRepository repository;
    private final DocumentSequenceProvisioner provisioner;

    public String next(Long tenantId, DocumentType type) {
        String series = type.seriesCode();
        provisioner.ensureExists(tenantId, series);   // committed in its own txn before we lock

        DocumentSequence seq = repository.findByTenantIdAndSeriesCode(tenantId, series)
                .orElseThrow(() -> new IllegalStateException(
                        "Document sequence row missing after provisioning: " + series));
        long next = seq.getLastValue() + 1;
        seq.setLastValue(next);
        repository.saveAndFlush(seq);

        return String.format("%s-%02d-%04d", series, Year.now().getValue() % 100, next);
    }
}
