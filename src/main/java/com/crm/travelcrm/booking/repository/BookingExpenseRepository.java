package com.crm.travelcrm.booking.repository;

import com.crm.travelcrm.booking.entity.BookingExpense;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every finder here is bound to a booking id and filters {@code deletedAt IS NULL}. There is
 * deliberately no {@code findByPublicId(...)} on its own: a lookup that is not scoped to the
 * already-authorised parent booking would resolve an expense belonging to a booking the caller
 * cannot see. This mirrors {@code BookingPaymentRepository} / {@code BookingServiceItemRepository}.
 *
 * <p>Note that {@code findById} and friends are NOT usable on this repository —
 * {@code TenantIsolationArchTest} fails the build on any primary-key lookup against a
 * {@code BaseTenantEntity}, because Hibernate's {@code tenantFilter} does not apply to
 * {@code EntityManager.find()}.
 */
@Repository
public interface BookingExpenseRepository extends JpaRepository<BookingExpense, Long> {

    /** Complete audit timeline, including soft-deleted entries that become removal events. */
    List<BookingExpense> findByBookingIdOrderByIdAsc(Long bookingId);

    /** The ledger for a booking, most recent cost first. */
    List<BookingExpense> findByBookingIdAndDeletedAtIsNullOrderByExpenseDateDescIdDesc(Long bookingId);

    /** One expense, scoped to its booking so a foreign publicId can never be reached. */
    Optional<BookingExpense> findByPublicIdAndBookingIdAndDeletedAtIsNull(UUID publicId, Long bookingId);

    /**
     * One expense INCLUDING a soft-deleted one — the single finder that omits the {@code deletedAt}
     * predicate, because restore is the one operation whose target is by definition already deleted.
     * Still scoped to the already-authorised booking, so it is no weaker than its sibling: the only
     * extra row it can reach is a trashed line on a booking the caller can already see.
     */
    Optional<BookingExpense> findByPublicIdAndBookingId(UUID publicId, Long bookingId);

    /**
     * Total agency-internal cost on a booking — the {@code totalInternalCosts} term of
     * {@code netProfit}. VENDOR rows and soft-deleted rows are excluded by the query itself, so no
     * caller can accidentally sum the wrong set.
     *
     * <p>Aggregated in SQL rather than folded in Java (unlike the ledger summary, whose overdue
     * split needs {@code @Transient} helpers): this runs on every expense write and every booking
     * edit, and it only ever needs one number.
     */
    @Query("""
           SELECT COALESCE(SUM(e.amount), 0) FROM BookingExpense e
           WHERE e.bookingId = :bookingId
             AND e.costType = com.crm.travelcrm.booking.enums.ExpenseCostType.INTERNAL
             AND e.deletedAt IS NULL
           """)
    BigDecimal sumInternalCosts(@Param("bookingId") Long bookingId);

    /**
     * The marketplace payable rows on a booking — VENDOR lines carrying a marketplace link.
     *
     * <p>Sibling of {@link #sumInternalCosts} and it runs in the same places, so its cost profile is
     * the one that query already accepts ("on every expense write and every booking edit").</p>
     *
     * <p>Why it exists: {@code ExpenseCostType}'s contract is that {@code Booking.vendorCost}
     * "already represents everything paid to suppliers", which is exactly why VENDOR rows are kept
     * out of {@code totalInternalCosts}. A marketplace payable is a VENDOR row the tenant never
     * typed, so the scalar has to be maintained for it — otherwise {@code netProfit} is overstated
     * by the payable, and {@code CancellationCalculator} freezes {@code sunkVendorCost} wrong,
     * permanently.</p>
     *
     * <p>The {@code costType = VENDOR} predicate is load-bearing, not decoration. Without it this sum
     * and {@link #sumInternalCosts} were not disjoint — the sibling filters on {@code costType} with
     * no marketplace predicate, this one filtered on the marketplace link with no {@code costType}
     * predicate, so a marketplace row reclassified to INTERNAL landed in BOTH. {@code netProfit} is
     * {@code customerAmount − vendorCost − totalInternalCosts} and the payable sits inside
     * {@code vendorCost}, so the same rupees were subtracted twice.</p>
     */
    @Query("""
           SELECT COALESCE(SUM(e.amount), 0) FROM BookingExpense e
           WHERE e.bookingId = :bookingId
             AND e.marketplaceBookingPublicId IS NOT NULL
             AND e.costType = com.crm.travelcrm.booking.enums.ExpenseCostType.VENDOR
             AND e.deletedAt IS NULL
           """)
    BigDecimal sumMarketplacePayable(@Param("bookingId") Long bookingId);

    /**
     * The marketplace payable row, INCLUDING a soft-deleted one — see the note on the matching
     * finder in {@code BookingServiceItemRepository}. A replay must un-delete rather than insert.
     */
    Optional<BookingExpense> findByMarketplaceBookingPublicId(UUID marketplaceBookingPublicId);
}
