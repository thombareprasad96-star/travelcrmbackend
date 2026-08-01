package com.crm.travelcrm.lead.util;

import com.crm.travelcrm.lead.entity.LeadSequence;
import com.crm.travelcrm.lead.repository.LeadRepository;
import com.crm.travelcrm.lead.repository.LeadSequenceRepository;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.dao.DataIntegrityViolationException;
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
    private final LeadRepository leadRepository;

    /**
     * Reserve and return the next reference for {@code tenantId}. The counter row is
     * locked for the duration of the surrounding transaction; the increment is therefore
     * atomic per tenant.
     */
    public String generate(Long tenantId) {
        LeadSequence seq = sequenceRepository.findByTenantId(tenantId)
                .orElseGet(() -> createInitial(tenantId));

        long next = seq.getLastValue() + 1;
        seq.setLastValue(next);
        sequenceRepository.saveAndFlush(seq);

        String code = String.format("%s-%02d-%04d", PREFIX, Year.now().getValue() % 100, next);
        log.debug("Generated lead reference {} for tenantId {}", code, tenantId);
        return code;
    }

    /**
     * Lazily create the tenant's counter row on first use. Under a concurrent first-ever
     * lead two threads could both miss the row and try to insert; the UNIQUE constraint
     * on {@code tenant_id} lets exactly one win, and the loser re-reads it under the lock.
     *
     * <p><b>Starts at the tenant's existing lead count, not at 0 — and that difference from
     * {@code BookingCodeGenerator} is the whole point.</b> Bookings shipped with their code column
     * from day one, so a missing counter genuinely means "no bookings yet". Leads did not: a live
     * tenant has rows that {@code db/indexes.sql} back-filled with {@code LD-YY-0001…N}. Starting a
     * fresh counter at 0 there would re-issue {@code LD-YY-0001} and the very first lead created
     * after the upgrade would die on {@code uq_leads_code_tenant}.
     *
     * <p>The SQL seed in {@code db/indexes.sql} normally makes this path unreachable on an upgraded
     * database — but {@code spring.sql.init.continue-on-error=true} means that statement can fail
     * SILENTLY, and {@code SQL_INIT_MODE=never} (set at the Flyway cutover) skips it entirely. This
     * makes the collision impossible either way instead of trusting a statement nobody watches.
     */
    private LeadSequence createInitial(Long tenantId) {
        try {
            long existing = leadRepository.countByTenantId(tenantId);
            if (existing > 0) {
                log.info("No lead counter row for tenant {} but {} lead(s) exist — seeding the "
                        + "counter from the row count so back-filled codes are never re-issued.",
                        tenantId, existing);
            }
            return sequenceRepository.saveAndFlush(
                    LeadSequence.builder().tenantId(tenantId).lastValue(existing).build());
        } catch (DataIntegrityViolationException raceLost) {
            log.debug("Lead sequence row for tenant {} created concurrently — re-reading", tenantId);
            return sequenceRepository.findByTenantId(tenantId)
                    .orElseThrow(() -> raceLost);
        }
    }
}
