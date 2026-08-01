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
}
