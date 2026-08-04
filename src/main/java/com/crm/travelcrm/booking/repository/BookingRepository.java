package com.crm.travelcrm.booking.repository;

import com.crm.travelcrm.booking.dto.CustomerBookingMetrics;
import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.enums.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookingRepository extends JpaRepository<Booking, Long>,
        JpaSpecificationExecutor<Booking> {

    // ── Lookup ───────────────────────────────────────────────────────────────

    Optional<Booking> findByPublicIdAndDeletedAtIsNull(UUID publicId);

    /**
     * Tenant-parameterised by-publicId lookup.
     *
     * <p>The finder above carries no tenant predicate and relies entirely on the Hibernate
     * {@code tenantFilter} — which {@code TenantFilterAspect} enables only on {@code @Transactional}
     * methods and which fails OPEN when {@code TenantContext} is empty. Any path that may run without
     * a request-scoped tenant (marketplace projection, a scheduler, anything reached through
     * {@code TenantScope}) must spell the tenant out instead of trusting the ambient filter.</p>
     */
    Optional<Booking> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    /**
     * The same rule for an internal id.
     *
     * <p>Needed because a child row ({@code BookingExpense.bookingId}) names its parent by primary
     * key, and {@code findById} on a {@code BaseTenantEntity} is a documented cross-tenant read —
     * {@code EntityManager.find()} never sees the Hibernate filter at all. {@code
     * TenantIsolationArchTest} fails the build on the bare call, which is exactly what caught it.</p>
     */
    Optional<Booking> findByIdAndTenantIdAndDeletedAtIsNull(Long id, Long tenantId);

    /**
     * The live booking already made from this lead, if any — the duplicate guard, addressable by the
     * lead's public UUID.
     *
     * <p>{@code convertLeadToBooking} has this check; {@code create()} does not, yet it accepts and
     * links a {@code leadPublicId} all the same. Any new path that creates a booking against a lead
     * has to run it, or one enquiry ends up with two bookings, two quota slots and — for a sub-agent
     * owner — commission accrued twice on the same sale.</p>
     *
     * <p>CANCELLED is excluded deliberately, matching the sibling finder: cancelling reopens the lead,
     * so a reopened lead must stay re-convertible even though its old cancelled booking still exists.</p>
     */
    @Query("""
            SELECT b FROM Booking b
            WHERE b.sourceLeadPublicId = :leadPublicId
              AND b.tenantId = :tenantId
              AND b.status <> com.crm.travelcrm.booking.enums.BookingStatus.CANCELLED
              AND b.deletedAt IS NULL
            ORDER BY b.id DESC
            LIMIT 1
            """)
    Optional<Booking> findFirstByLeadPublicIdActive(@Param("leadPublicId") UUID leadPublicId,
                                                    @Param("tenantId") Long tenantId);

    Optional<Booking> findByBookingCodeAndDeletedAtIsNull(String bookingCode);

    Optional<Booking> findByIdAndDeletedAtIsNull(Long id);

    Optional<Booking> findTopByOrderByIdDesc();

    List<Booking> findAllByCustomerIdAndDeletedAtIsNull(Long customerId);

    boolean existsByBookingCode(String bookingCode);

    // Customer-removal guard on cancel: count this customer's OTHER active (non-trashed) bookings,
    // excluding the one being cancelled. Zero ⇒ the derived customer can be moved to Trash too;
    // >0 ⇒ it's a repeat customer, so it is kept.
    long countByCustomerIdAndTenantIdAndDeletedAtIsNullAndIdNot(
            Long customerId, Long tenantId, Long id);

    // How many live bookings pin a given cancellation-policy version — guards the policy delete so a
    // version a booking still relies on for its cancellation math can never be physically removed.
    long countByCancellationPolicyPublicIdAndDeletedAtIsNull(java.util.UUID cancellationPolicyPublicId);

    // Referential-integrity guard for master data: is any active (non-trashed) booking still
    // pointing at this destination (Booking.destinationId is a logical FK to destination master)?
    boolean existsByDestinationIdAndTenantIdAndDeletedAtIsNull(Long destinationId, Long tenantId);

    // ── Lead → Booking conversion guards (tenant-scoped) ──────────────────────
    // Duplicate guard: a lead must not be silently converted into a second booking.
    boolean existsByLeadIdAndTenantIdAndDeletedAtIsNull(Long leadId, Long tenantId);

    // The still-live booking a lead is already converted into (newest first), EXCLUDING a
    // given status. Double-convert protection passes CANCELLED here so a reopened lead whose
    // only prior booking was cancelled can be converted again, while a lead with a genuinely
    // active (PENDING/CONFIRMED/COMPLETED/REFUNDED) booking is still blocked. A cancelled
    // booking is retained (deleted_at stays null), so filtering on soft-delete alone is not
    // enough to tell "already booked" from "was booked, then cancelled".
    Optional<Booking> findFirstByLeadIdAndTenantIdAndStatusNotAndDeletedAtIsNullOrderByIdDesc(
            Long leadId, Long tenantId, BookingStatus status);

    // ── Stats queries ────────────────────────────────────────────────────────

    @Query("""
            SELECT b
            FROM Booking b
            WHERE b.tenantId = :tenantId
              AND b.deletedAt IS NULL
              AND b.bookingDate BETWEEN :from AND :to
            ORDER BY b.bookingDate DESC, b.id DESC
            """)
    List<Booking> findDashboardBookings(@Param("tenantId") Long tenantId,
                                        @Param("from") LocalDate from,
                                        @Param("to") LocalDate to);

    long countByDeletedAtIsNull();

    long countByStatusAndDeletedAtIsNull(BookingStatus status);

    // Money aggregates exclude CANCELLED and REFUNDED so the finance dashboard reflects real
    // earned revenue / receivables — a cancelled booking is retained (status=CANCELLED, not
    // soft-deleted) with its money fields intact, so filtering on deletedAt alone would count its
    // balance as receivables and its amount as revenue. The status is stored as EnumType.STRING,
    // so the string-literal NOT IN mirrors the existing sumTotalRefund predicate.
    @Query("SELECT COALESCE(SUM(b.customerAmount), 0) FROM Booking b WHERE b.deletedAt IS NULL AND b.status NOT IN ('CANCELLED', 'REFUNDED')")
    BigDecimal sumTotalRevenue();

    @Query("SELECT COALESCE(SUM(b.paidAmount), 0) FROM Booking b WHERE b.deletedAt IS NULL AND b.status NOT IN ('CANCELLED', 'REFUNDED')")
    BigDecimal sumTotalCollected();

    /**
     * Profit on CANCELLED/REFUNDED bookings — the mirror of {@code sumNetProfit}, which covers only
     * delivered travel. On a cancelled row {@code netProfit} holds the revised figure (retained
     * charge less unrecovered vendor and internal costs), written by the cancel flow and kept in
     * step by {@code BookingProfitService}, so this is a plain sum with no join.
     *
     * <p>Reported separately rather than folded into the headline profit: it is a churn and
     * policy-pricing metric, not a sales one, and it is frequently negative — a 25% cancellation
     * slab rarely covers a 100%-sunk visa or air cost.
     */
    @Query("SELECT COALESCE(SUM(b.netProfit), 0) FROM Booking b WHERE b.deletedAt IS NULL AND b.status IN ('CANCELLED', 'REFUNDED')")
    BigDecimal sumCancelledProfit();

    @Query("SELECT COALESCE(SUM(b.totalPayable - b.paidAmount), 0) FROM Booking b WHERE b.deletedAt IS NULL AND b.status NOT IN ('CANCELLED', 'REFUNDED')")
    BigDecimal sumTotalPending();

    // The real money disbursed back to customers — sum of the actual refunded counter across all
    // bookings (0 on any booking that was never refunded). Status-independent on purpose: a partially
    // refunded booking is still CANCELLED but its payout must still count here.
    @Query("SELECT COALESCE(SUM(b.refundedAmount), 0) FROM Booking b WHERE b.deletedAt IS NULL")
    BigDecimal sumTotalRefund();

    @Query("SELECT COALESCE(SUM(b.netProfit), 0) FROM Booking b WHERE b.deletedAt IS NULL AND b.status NOT IN ('CANCELLED', 'REFUNDED')")
    BigDecimal sumNetProfit();

    @Query("SELECT COALESCE(SUM(b.gst), 0) FROM Booking b WHERE b.deletedAt IS NULL AND b.status NOT IN ('CANCELLED', 'REFUNDED')")
    BigDecimal sumGstCollected();

    @Query("SELECT COALESCE(SUM(b.tcs), 0) FROM Booking b WHERE b.deletedAt IS NULL AND b.status NOT IN ('CANCELLED', 'REFUNDED')")
    BigDecimal sumTcsCollected();

    // ── Customer-module aggregates (tenant-scoped) ─────────────────────────────
    // Used by the customer module to enrich profiles and the stats dashboard with
    // booking-derived figures. All run in the database — never in memory.

    /** One ordered history row per active booking for a customer. */
    List<Booking> findAllByCustomerIdAndDeletedAtIsNullOrderByBookingDateDesc(Long customerId);

    // ── Traveler portal: object-level ownership (a traveler sees ONLY their own customer) ──
    Optional<Booking> findByPublicIdAndCustomerIdAndDeletedAtIsNull(UUID publicId, Long customerId);

    /**
     * Portal-access gate. Does this customer have at least one non-cancelled, non-trashed booking
     * whose travel date is still within the access window — i.e. {@code travelDate >= minTravelDate}
     * (where {@code minTravelDate = today - accessDays}, so equivalently {@code travelDate + accessDays
     * >= today})? Future bookings always qualify; a booking whose travel was more than {@code accessDays}
     * ago no longer grants portal access. Cancelled bookings never grant access.
     */
    boolean existsByTenantIdAndCustomerIdAndStatusNotAndTravelDateGreaterThanEqualAndDeletedAtIsNull(
            Long tenantId, Long customerId, BookingStatus status, LocalDate minTravelDate);

    /**
     * Grouped booking metrics for a batch of customers, scoped to the tenant.
     * Returns at most one row per customer that actually has bookings — customers
     * with none simply won't appear (the service defaults them to zero).
     */
    @Query("""
            SELECT b.customerId          AS customerId,
                   COUNT(b)              AS bookingCount,
                   COALESCE(SUM(b.customerAmount), 0) AS totalSpent,
                   MAX(b.bookingDate)    AS lastBookingDate
            FROM Booking b
            WHERE b.deletedAt IS NULL
              AND b.tenantId = :tenantId
              AND b.customerId IN :customerIds
            GROUP BY b.customerId
            """)
    List<CustomerBookingMetrics> findMetricsByCustomerIds(
            @Param("tenantId") Long tenantId,
            @Param("customerIds") List<Long> customerIds);

    /** Lifetime revenue (sum of customerAmount) for one tenant. */
    @Query("SELECT COALESCE(SUM(b.customerAmount), 0) FROM Booking b " +
            "WHERE b.deletedAt IS NULL AND b.tenantId = :tenantId")
    BigDecimal sumRevenueByTenant(@Param("tenantId") Long tenantId);

    /** Total active bookings for one tenant. */
    @Query("SELECT COUNT(b) FROM Booking b WHERE b.deletedAt IS NULL AND b.tenantId = :tenantId")
    long countByTenant(@Param("tenantId") Long tenantId);

    /**
     * New-bookings-this-month grouped by tenant across ALL tenants (SuperAdmin usage dashboard).
     * <b>NATIVE</b> so the Hibernate {@code @Filter("tenantFilter")} never rewrites it — a guaranteed
     * cross-tenant count even if ever invoked inside a tenant context (a JPQL query would be silently
     * scoped). Keyed on {@code booking_date} (the business date) within {@code [monthStart, nextMonthStart)}.
     * Each row is {@code [tenant_id (Long), count (Long)]}.
     */
    @Query(value = "SELECT b.tenant_id, COUNT(*) FROM bookings b " +
            "WHERE b.deleted_at IS NULL AND b.booking_date >= :monthStart AND b.booking_date < :nextMonthStart " +
            "GROUP BY b.tenant_id", nativeQuery = true)
    List<Object[]> countBookingsByTenantForMonth(@Param("monthStart") LocalDate monthStart,
                                                 @Param("nextMonthStart") LocalDate nextMonthStart);

    /** New bookings in {@code [monthStart, nextMonthStart)} for ONE tenant — the monthly-quota gate count. */
    @Query("SELECT COUNT(b) FROM Booking b WHERE b.deletedAt IS NULL AND b.tenantId = :tenantId " +
            "AND b.bookingDate >= :monthStart AND b.bookingDate < :nextMonthStart")
    long countByTenantForMonth(@Param("tenantId") Long tenantId,
                               @Param("monthStart") LocalDate monthStart,
                               @Param("nextMonthStart") LocalDate nextMonthStart);

    /** Distinct customer ids with 3+ active bookings — feeds "repeat customers". */
    @Query("""
            SELECT b.customerId
            FROM Booking b
            WHERE b.deletedAt IS NULL AND b.tenantId = :tenantId
            GROUP BY b.customerId
            HAVING COUNT(b) >= 3
            """)
    List<Long> findRepeatCustomerIds(@Param("tenantId") Long tenantId);

    /**
     * Per-owner booking aggregate for the B2B sub-agent roll-up: one row per {@code ownerUserId}
     * with [ownerUserId, bookingCount, totalSaleValue (customerAmount), totalCollected (paidAmount)].
     * Includes all non-deleted bookings (cancelled ones were still sales the sub-agent generated).
     */
    @Query("""
            SELECT b.ownerUserId, COUNT(b),
                   COALESCE(SUM(b.customerAmount), 0), COALESCE(SUM(b.paidAmount), 0)
            FROM Booking b
            WHERE b.deletedAt IS NULL AND b.tenantId = :tenantId AND b.ownerUserId IS NOT NULL
            GROUP BY b.ownerUserId
            """)
    List<Object[]> aggregateByOwner(@Param("tenantId") Long tenantId);
}
