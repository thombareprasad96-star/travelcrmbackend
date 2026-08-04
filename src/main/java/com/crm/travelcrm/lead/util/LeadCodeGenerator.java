package com.crm.travelcrm.lead.util;

import com.crm.travelcrm.lead.entity.LeadSequence;
import com.crm.travelcrm.lead.repository.LeadSequenceRepository;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.time.Year;

/**
 * Generates the human-readable lead reference {@code LD-YY-NNNN} (e.g. {@code LD-26-0001}).
 *
 * <p>This is the lead's <b>display</b> identity, deliberately separate from {@code publicId}: the
 * UUID stays the API/routing key (every URL, every FE link), while this is what a human says out
 * loud on a call. Bookings already work exactly this way ({@code BKG-YY-NNNN}) — before this
 * existed the lead UI had nothing to print but a raw UUID.</p>
 *
 * <p>Modelled on {@code BookingCodeGenerator}, NOT on {@code CustomerCodeGenerator}: the number is
 * <b>tenant-scoped</b> and <b>concurrency-safe</b>, drawn from a per-tenant counter row
 * ({@link LeadSequence}) read under a pessimistic write lock, so two simultaneous creations in the
 * same tenant can never collide. The "parse the tenant's highest existing code" scheme is race-prone
 * and breaks the moment a non-conforming code exists — which is precisely what a backfill produces.</p>
 *
 * <p>Must be called inside the caller's {@code @Transactional} unit ({@code LeadServiceImpl.createLead}
 * is transactional) so the row lock is held for the whole lead-creation transaction.</p>
 */
@Component
@RequiredArgsConstructor
public class LeadCodeGenerator {

    private static final Logger log = LogManager.getLogger(LeadCodeGenerator.class);

    private static final String PREFIX = "LD";

    private final LeadSequenceRepository sequenceRepository;
    private final LeadSequenceProvisioner provisioner;

    /**
     * Reserve and return the next reference for {@code tenantId}. The counter row is
     * locked for the duration of the surrounding transaction; the increment is therefore
     * atomic per tenant.
     */
    public String generate(Long tenantId) {
        // Provision FIRST, in its own transaction, so a lost create-race cannot poison this one —
        // and before the lock below, so the two never contend. See LeadSequenceProvisioner.
        provisioner.ensureExists(tenantId);

        LeadSequence seq = sequenceRepository.findByTenantId(tenantId)
                .orElseThrow(() -> new IllegalStateException(
                        "Lead counter row missing for tenant " + tenantId + " immediately after "
                        + "provisioning — the row was deleted concurrently."));

        long next = seq.getLastValue() + 1;
        seq.setLastValue(next);
        sequenceRepository.saveAndFlush(seq);

        String code = String.format("%s-%02d-%04d", PREFIX, Year.now().getValue() % 100, next);
        log.debug("Generated lead reference {} for tenantId {}", code, tenantId);
        return code;
    }

}
