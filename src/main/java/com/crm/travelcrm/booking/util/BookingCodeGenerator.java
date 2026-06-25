package com.crm.travelcrm.booking.util;

import com.crm.travelcrm.booking.entity.BookingSequence;
import com.crm.travelcrm.booking.repository.BookingSequenceRepository;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.Year;

/**
 * Generates the human-readable booking reference {@code BKG-YY-NNNN}
 * (e.g. {@code BKG-26-0001}).
 *
 * <p>The number is <b>tenant-scoped</b> and <b>concurrency-safe</b>: it is drawn from a
 * per-tenant counter row ({@link BookingSequence}) read under a pessimistic write lock,
 * so two simultaneous bookings/conversions in the same tenant can never collide. This
 * replaces the old global {@code findTopByOrderByIdDesc()} + string-parse scheme, which
 * was neither tenant-scoped nor race-safe and broke once a non-numeric code existed.</p>
 *
 * <p>Must be called inside the caller's {@code @Transactional} unit (every caller —
 * {@code BookingServiceImpl.create} and the lead-conversion flow — is transactional) so
 * the row lock is held for the whole booking-creation transaction.</p>
 */
@Component
@RequiredArgsConstructor
public class BookingCodeGenerator {

    private static final Logger log = LogManager.getLogger(BookingCodeGenerator.class);

    private static final String PREFIX = "BKG";

    private final BookingSequenceRepository sequenceRepository;

    /**
     * Reserve and return the next reference for {@code tenantId}. The counter row is
     * locked for the duration of the surrounding transaction; the increment is therefore
     * atomic per tenant.
     */
    public String generate(Long tenantId) {
        BookingSequence seq = sequenceRepository.findByTenantId(tenantId)
                .orElseGet(() -> createInitial(tenantId));

        long next = seq.getLastValue() + 1;
        seq.setLastValue(next);
        sequenceRepository.saveAndFlush(seq);

        String code = String.format("%s-%02d-%04d", PREFIX, Year.now().getValue() % 100, next);
        log.debug("Generated booking reference {} for tenantId {}", code, tenantId);
        return code;
    }

    /**
     * Lazily create the tenant's counter row on first use. Under a concurrent first-ever
     * booking two threads could both miss the row and try to insert; the UNIQUE constraint
     * on {@code tenant_id} lets exactly one win, and the loser re-reads it under the lock.
     */
    private BookingSequence createInitial(Long tenantId) {
        try {
            return sequenceRepository.saveAndFlush(
                    BookingSequence.builder().tenantId(tenantId).lastValue(0L).build());
        } catch (DataIntegrityViolationException raceLost) {
            log.debug("Booking sequence row for tenant {} created concurrently — re-reading", tenantId);
            return sequenceRepository.findByTenantId(tenantId)
                    .orElseThrow(() -> raceLost);
        }
    }
}