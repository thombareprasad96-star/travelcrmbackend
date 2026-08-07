package com.crm.travelcrm.lead.util;

import com.crm.travelcrm.lead.entity.LeadSequence;
import com.crm.travelcrm.lead.repository.LeadRepository;
import com.crm.travelcrm.lead.repository.LeadSequenceRepository;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ensures a {@link LeadSequence} row exists BEFORE {@link LeadCodeGenerator} takes its pessimistic
 * lock. Runs in its OWN transaction ({@link Propagation#REQUIRES_NEW}) so that if two requests race
 * to create a tenant's first-ever lead, the losing INSERT's constraint violation aborts only this
 * short inner transaction — never the caller's lead-creation transaction.
 *
 * <p>This is a SEPARATE bean, not a private method on the generator, and that is load-bearing:
 * {@code REQUIRES_NEW} is applied by the Spring proxy, and a self-invocation goes through
 * {@code this} rather than the proxy, silently running in the caller's transaction and reinstating
 * the very bug this class exists to remove.
 *
 * <p><b>Why the old catch-and-re-read was broken.</b> The generator used to insert optimistically
 * and, on {@link DataIntegrityViolationException}, re-read the row inside the SAME transaction. On
 * Postgres a constraint violation aborts the current transaction ({@code 25P02 — current transaction
 * is aborted, commands ignored until end of transaction block}), so that re-read could only throw;
 * the recovery branch was unreachable. Independently, Hibernate marks the transaction rollback-only
 * after the {@code PersistenceException}, so even a successful read would have died at commit with
 * {@code UnexpectedRollbackException}. {@code DocumentSequenceProvisioner} already avoids this for
 * cancellation documents — this is the same remedy for leads.
 */
@Component
@RequiredArgsConstructor
public class LeadSequenceProvisioner {

    private static final Logger log = LogManager.getLogger(LeadSequenceProvisioner.class);

    private final LeadSequenceRepository sequenceRepository;
    private final LeadRepository leadRepository;

    /**
     * Create the tenant's counter row if it is missing. A no-op — one cheap indexed existence check —
     * on every call after the first, which is the overwhelmingly common case.
     *
     * <p><b>Seeds from the tenant's existing lead count, not 0</b>, and that difference from
     * {@code BookingSequenceProvisioner} is the whole point. Bookings shipped with their code column
     * from day one, so a missing counter genuinely means "no bookings yet". Leads did not: a live
     * tenant has rows that {@code V2__lead_code.sql} back-filled with {@code LD-YY-0001…N}. Starting
     * a fresh counter at 0 there would re-issue {@code LD-YY-0001} and the first lead created after
     * the upgrade would die on the unique lead-code constraint.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureExists(Long tenantId) {
        if (sequenceRepository.existsByTenantId(tenantId)) {
            return;
        }
        try {
            long existing = leadRepository.countByTenantId(tenantId);
            if (existing > 0) {
                log.info("No lead counter row for tenant {} but {} lead(s) exist — seeding the "
                        + "counter from the row count so back-filled codes are never re-issued.",
                        tenantId, existing);
            }
            sequenceRepository.saveAndFlush(
                    LeadSequence.builder().tenantId(tenantId).lastValue(existing).build());
        } catch (DataIntegrityViolationException raceLost) {
            // Another request created it first. This inner transaction rolls back harmlessly and the
            // caller's transaction is untouched — the row it needs now exists either way.
            log.debug("Lead sequence row for tenant {} created concurrently — nothing to do", tenantId);
        }
    }
}
