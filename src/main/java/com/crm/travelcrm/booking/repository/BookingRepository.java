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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookingRepository extends JpaRepository<Booking, Long>,
        JpaSpecificationExecutor<Booking> {

    // ── Lookup ───────────────────────────────────────────────────────────────

    Optional<Booking> findByPublicIdAndDeletedAtIsNull(UUID publicId);

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

    long countByDeletedAtIsNull();

    long countByStatusAndDeletedAtIsNull(BookingStatus status);

    @Query("SELECT COALESCE(SUM(b.customerAmount), 0) FROM Booking b WHERE b.deletedAt IS NULL")
    BigDecimal sumTotalRevenue();

    @Query("SELECT COALESCE(SUM(b.paidAmount), 0) FROM Booking b WHERE b.deletedAt IS NULL")
    BigDecimal sumTotalCollected();

    @Query("SELECT COALESCE(SUM(b.totalPayable - b.paidAmount), 0) FROM Booking b WHERE b.deletedAt IS NULL")
    BigDecimal sumTotalPending();

    @Query("SELECT COALESCE(SUM(b.customerAmount), 0) FROM Booking b WHERE b.deletedAt IS NULL AND b.status = 'REFUNDED'")
    BigDecimal sumTotalRefund();

    @Query("SELECT COALESCE(SUM(b.netProfit), 0) FROM Booking b WHERE b.deletedAt IS NULL")
    BigDecimal sumNetProfit();

    @Query("SELECT COALESCE(SUM(b.gst), 0) FROM Booking b WHERE b.deletedAt IS NULL")
    BigDecimal sumGstCollected();

    @Query("SELECT COALESCE(SUM(b.tcs), 0) FROM Booking b WHERE b.deletedAt IS NULL")
    BigDecimal sumTcsCollected();

    // ── Customer-module aggregates (tenant-scoped) ─────────────────────────────
    // Used by the customer module to enrich profiles and the stats dashboard with
    // booking-derived figures. All run in the database — never in memory.

    /** One ordered history row per active booking for a customer. */
    List<Booking> findAllByCustomerIdAndDeletedAtIsNullOrderByBookingDateDesc(Long customerId);

    // ── Traveler portal: object-level ownership (a traveler sees ONLY their own customer) ──
    Optional<Booking> findByPublicIdAndCustomerIdAndDeletedAtIsNull(UUID publicId, Long customerId);

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

    /** Distinct customer ids with 3+ active bookings — feeds "repeat customers". */
    @Query("""
            SELECT b.customerId
            FROM Booking b
            WHERE b.deletedAt IS NULL AND b.tenantId = :tenantId
            GROUP BY b.customerId
            HAVING COUNT(b) >= 3
            """)
    List<Long> findRepeatCustomerIds(@Param("tenantId") Long tenantId);
}