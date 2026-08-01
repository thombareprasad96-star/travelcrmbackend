package com.crm.travelcrm.booking.cancellation.repository;

import com.crm.travelcrm.booking.cancellation.entity.BookingCancellation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface BookingCancellationRepository extends JpaRepository<BookingCancellation, Long> {

    /** The (single) cancellation record for a booking. */
    Optional<BookingCancellation> findByBookingIdAndDeletedAtIsNull(Long bookingId);

    boolean existsByBookingIdAndDeletedAtIsNull(Long bookingId);

    /**
     * Total cancellation charge the agency RETAINED in a period — the second term of
     * {@code agencyRevenue = activeBookingCustomerAmount + totalRetainedCancellationCharges}.
     *
     * <p><b>Sums {@code finalChargeBase}, not {@code totalRetained}, and that is the whole point.</b>
     * {@code totalRetained} bundles {@code gstOnCharge} (output tax owed to the government) and
     * {@code tcsRetained} (the customer's own tax credit). Neither is the agency's money — Ind AS 115
     * excludes amounts collected on behalf of third parties from the transaction price, and revenue
     * is presented net of GST regardless of whether the customer price was quoted inclusive. Summing
     * {@code totalRetained} would book government tax as agency revenue.
     *
     * <p>Keyed on {@code cancelDate}, the date the retention crystallises — not the original booking
     * date, which would attribute this period's forfeiture to a period that is already closed.
     *
     * <p>Explicitly tenant-scoped rather than trusting the Hibernate filter: {@code TenantFilterAspect}
     * fails OPEN when {@code TenantContext} is null, and a cross-tenant revenue total is the worst
     * possible failure mode for this particular query.
     */
    /*
     * The EXISTS clause is load-bearing. A CANCELLED booking is deletable (BookingServiceImpl.delete
     * blocks only CONFIRMED/COMPLETED), soft-deleting a booking does NOT cascade to its cancellation
     * row, and BookingCancellation is not a TrashableType — so after the 30-day purge removes the
     * booking the cancellation row survives with no parent at all (booking_id carries no DB FK, by
     * design). Without this predicate a trashed booking would report ZERO revenue and ZERO profit
     * from findDashboardBookings — which does filter deletedAt — while still contributing its full
     * retained charge to agencyRevenue in the SAME response, permanently and unreconcilably.
     */
    @Query("""
           SELECT COALESCE(SUM(c.finalChargeBase), 0) FROM BookingCancellation c
           WHERE c.tenantId = :tenantId
             AND c.deletedAt IS NULL
             AND c.cancelDate BETWEEN :from AND :to
             AND EXISTS (SELECT 1 FROM Booking b
                         WHERE b.id = c.bookingId AND b.deletedAt IS NULL)
           """)
    BigDecimal sumRetainedChargeBase(@Param("tenantId") Long tenantId,
                                     @Param("from") LocalDate from,
                                     @Param("to") LocalDate to);
}