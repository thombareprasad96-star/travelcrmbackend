package com.crm.travelcrm.hotelmarketplace.commission.repository;

import com.crm.travelcrm.hotelmarketplace.commission.entity.PlatformCommissionEntry;
import com.crm.travelcrm.hotelmarketplace.commission.enums.CommissionEntryStatus;
import com.crm.travelcrm.hotelmarketplace.commission.enums.CommissionEntryType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The platform earning ledger.
 *
 * <p><b>SuperAdmin-only, top to bottom.</b> Every row carries the supplier cost and the platform's
 * margin, so there is no tenant-facing finder here and there must never be one — that is why the
 * queries below take no {@code tenantId} ownership term the way the booking repository does. The
 * {@code tenantId} parameters are report filters, not access control.</p>
 *
 * <p>Every read carries {@code deletedAt IS NULL}. A ledger row should never be soft-deleted, but the
 * unique reference-key index is partial on the same predicate, and a query that disagreed with the
 * index about which rows are live would make the idempotency probe lie.</p>
 */
public interface PlatformCommissionEntryRepository extends JpaRepository<PlatformCommissionEntry, Long> {

    /**
     * The idempotency probe. Called before every write; a hit means the event has already been
     * recorded and the caller must return the existing row rather than write a second one.
     */
    Optional<PlatformCommissionEntry> findByReferenceKeyAndDeletedAtIsNull(String referenceKey);

    Optional<PlatformCommissionEntry> findByPublicIdAndDeletedAtIsNull(UUID publicId);

    /** One booking's full history, oldest first — the order the events actually happened in. */
    List<PlatformCommissionEntry> findByHotelBookingIdAndDeletedAtIsNullOrderByIdAsc(Long hotelBookingId);

    /**
     * The net position on one booking: what the platform is left holding once reversals and
     * adjustments are applied.
     *
     * <p>A plain signed sum, which is the whole reason {@code amount} is signed. COALESCE because a
     * booking with no ledger rows yet is zero, not null.</p>
     */
    @Query("""
           SELECT COALESCE(SUM(e.amount), 0) FROM PlatformCommissionEntry e
           WHERE e.deletedAt IS NULL
             AND e.hotelBookingId = :hotelBookingId
           """)
    BigDecimal sumAmountForBooking(@Param("hotelBookingId") Long hotelBookingId);

    // ── SuperAdmin report ───────────────────────────────────────────────────
    // One null-tolerant filter block, repeated verbatim across the search and the three aggregates.
    // Keeping them textually identical is deliberate: a summary computed over a different row set
    // than the list it sits above is a reconciliation bug nobody reports, they just stop trusting
    // the number.

    @Query("""
           SELECT e FROM PlatformCommissionEntry e
           WHERE e.deletedAt IS NULL
             AND (:tenantId  IS NULL OR e.tenantId  = :tenantId)
             AND (:status    IS NULL OR e.status    = :status)
             AND (:entryType IS NULL OR e.entryType = :entryType)
             AND (:fromDate  IS NULL OR e.effectiveDate >= :fromDate)
             AND (:toDate    IS NULL OR e.effectiveDate <= :toDate)
           ORDER BY e.id DESC
           """)
    Page<PlatformCommissionEntry> searchForAdmin(@Param("tenantId") Long tenantId,
                                                 @Param("status") CommissionEntryStatus status,
                                                 @Param("entryType") CommissionEntryType entryType,
                                                 @Param("fromDate") LocalDate fromDate,
                                                 @Param("toDate") LocalDate toDate,
                                                 Pageable pageable);

    /** Σ signed amount over the filtered set — the authoritative "what did the platform earn" figure. */
    @Query("""
           SELECT COALESCE(SUM(e.amount), 0) FROM PlatformCommissionEntry e
           WHERE e.deletedAt IS NULL
             AND (:tenantId IS NULL OR e.tenantId = :tenantId)
             AND (:fromDate IS NULL OR e.effectiveDate >= :fromDate)
             AND (:toDate   IS NULL OR e.effectiveDate <= :toDate)
           """)
    BigDecimal sumNet(@Param("tenantId") Long tenantId,
                      @Param("fromDate") LocalDate fromDate,
                      @Param("toDate") LocalDate toDate);

    /** {@code [CommissionEntryType, Σ amount, count]} — gross accrued, reversed and adjusted in one hop. */
    @Query("""
           SELECT e.entryType, COALESCE(SUM(e.amount), 0), COUNT(e) FROM PlatformCommissionEntry e
           WHERE e.deletedAt IS NULL
             AND (:tenantId IS NULL OR e.tenantId = :tenantId)
             AND (:fromDate IS NULL OR e.effectiveDate >= :fromDate)
             AND (:toDate   IS NULL OR e.effectiveDate <= :toDate)
           GROUP BY e.entryType
           """)
    List<Object[]> sumGroupedByEntryType(@Param("tenantId") Long tenantId,
                                         @Param("fromDate") LocalDate fromDate,
                                         @Param("toDate") LocalDate toDate);

    /** {@code [CommissionEntryStatus, Σ amount, count]} — how much of the net is pending vs settled. */
    @Query("""
           SELECT e.status, COALESCE(SUM(e.amount), 0), COUNT(e) FROM PlatformCommissionEntry e
           WHERE e.deletedAt IS NULL
             AND (:tenantId IS NULL OR e.tenantId = :tenantId)
             AND (:fromDate IS NULL OR e.effectiveDate >= :fromDate)
             AND (:toDate   IS NULL OR e.effectiveDate <= :toDate)
           GROUP BY e.status
           """)
    List<Object[]> sumGroupedByStatus(@Param("tenantId") Long tenantId,
                                      @Param("fromDate") LocalDate fromDate,
                                      @Param("toDate") LocalDate toDate);

    /**
     * Live accrual rows on a booking that a status transition may still touch.
     *
     * <p>SETTLED accruals are excluded on purpose: money that has already been paid out cannot be
     * un-earned by flipping a column, and pretending otherwise would leave the ledger disagreeing with
     * the bank. That case needs a clawback row, not a status change.</p>
     */
    @Query("""
           SELECT e FROM PlatformCommissionEntry e
           WHERE e.deletedAt IS NULL
             AND e.hotelBookingId = :hotelBookingId
             AND e.entryType = com.crm.travelcrm.hotelmarketplace.commission.enums.CommissionEntryType.ACCRUAL
             AND e.status IN :statuses
           ORDER BY e.id ASC
           """)
    List<PlatformCommissionEntry> findAccrualsInStatus(@Param("hotelBookingId") Long hotelBookingId,
                                                       @Param("statuses") List<CommissionEntryStatus> statuses);

    /**
     * Bookings carrying an accrual that is still {@code PENDING} — the earning sweep's work list.
     *
     * <p>Returns booking ids rather than entries so the caller does one pass per booking instead of
     * one per row; {@code markEarnedForBooking} already handles every open accrual on a booking in a
     * single call.</p>
     *
     * <p>The set drains as bookings complete, so this is not an ever-growing scan: a row leaves the
     * result permanently the first time it is earned.</p>
     */
    @Query("""
           SELECT DISTINCT e.hotelBookingId FROM PlatformCommissionEntry e
           WHERE e.deletedAt IS NULL
             AND e.entryType = com.crm.travelcrm.hotelmarketplace.commission.enums.CommissionEntryType.ACCRUAL
             AND e.status = com.crm.travelcrm.hotelmarketplace.commission.enums.CommissionEntryStatus.PENDING
           ORDER BY e.hotelBookingId ASC
           """)
    List<Long> findBookingIdsWithPendingAccruals(Pageable pageable);
}
