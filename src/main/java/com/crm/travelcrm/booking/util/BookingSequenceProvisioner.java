package com.crm.travelcrm.booking.util;

import com.crm.travelcrm.booking.entity.BookingSequence;
import com.crm.travelcrm.booking.repository.BookingSequenceRepository;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ensures a {@link BookingSequence} row exists BEFORE {@link BookingCodeGenerator} takes its
 * pessimistic lock. Runs in its OWN transaction ({@link Propagation#REQUIRES_NEW}) so that if two
 * requests race to create a tenant's first-ever booking, the losing INSERT's constraint violation
 * aborts only this short inner transaction — never the caller's booking-creation transaction.
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
 * cancellation documents — this is the same remedy for bookings.
 */
@Component
@RequiredArgsConstructor
public class BookingSequenceProvisioner {

    private static final Logger log = LogManager.getLogger(BookingSequenceProvisioner.class);

    private final BookingSequenceRepository sequenceRepository;

    /**
     * Create the tenant's counter row if it is missing. A no-op — one cheap indexed existence check —
     * on every call after the first, which is the overwhelmingly common case.
     *
     * <p>Seeds at 0, unlike {@code LeadSequenceProvisioner}: bookings have carried
     * {@code booking_code} since V1, so a missing counter really does mean "no bookings yet" and
     * there are no back-filled codes to collide with.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureExists(Long tenantId) {
        if (sequenceRepository.existsByTenantId(tenantId)) {
            return;
        }
        try {
            sequenceRepository.saveAndFlush(
                    BookingSequence.builder().tenantId(tenantId).lastValue(0L).build());
        } catch (DataIntegrityViolationException raceLost) {
            // Another request created it first. This inner transaction rolls back harmlessly and the
            // caller's transaction is untouched — the row it needs now exists either way.
            log.debug("Booking sequence row for tenant {} created concurrently — nothing to do", tenantId);
        }
    }
}
