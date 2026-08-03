package com.crm.travelcrm.hotelmarketplace.booking.repository;

import com.crm.travelcrm.hotelmarketplace.booking.entity.PlatformHotelBooking;
import com.crm.travelcrm.hotelmarketplace.booking.enums.CrmSyncState;
import com.crm.travelcrm.hotelmarketplace.booking.enums.MarketplaceBookingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Marketplace booking requests.
 *
 * <p><b>Read the two audiences apart.</b> These rows carry a {@code tenantId} but extend
 * {@code BaseEntity}, so no Hibernate filter scopes them and {@code TenantIsolationArchTest} does not
 * see this repository either. A bare {@code findByPublicId} here would hand tenant A tenant B's
 * hotel, guest details and negotiated payable, and nothing in the build would fail.</p>
 *
 * <ul>
 *   <li>{@link #findByPublicIdAndTenantIdAndDeletedAtIsNull} — TENANT-facing. Always this one.</li>
 *   <li>{@link #findByPublicIdForUpdate} — SuperAdmin approval path only, deliberately un-scoped.</li>
 * </ul>
 */
public interface PlatformHotelBookingRepository extends JpaRepository<PlatformHotelBooking, Long> {

    // ── Tenant-facing: ownership is in the query, not left to the caller ────

    Optional<PlatformHotelBooking> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    Page<PlatformHotelBooking> findByTenantIdAndDeletedAtIsNullOrderByIdDesc(Long tenantId, Pageable pageable);

    /**
     * The tenant list, optionally narrowed to one state.
     *
     * <p>One query with a null-tolerant predicate rather than two finders, so the ownership term can
     * never be dropped by picking the wrong overload — which is the failure mode that matters on a
     * repository the Hibernate tenant filter does not cover.</p>
     */
    @Query("""
           SELECT b FROM PlatformHotelBooking b
           WHERE b.tenantId = :tenantId
             AND b.deletedAt IS NULL
             AND (:status IS NULL OR b.status = :status)
           ORDER BY b.id DESC
           """)
    Page<PlatformHotelBooking> searchForTenant(@Param("tenantId") Long tenantId,
                                               @Param("status") MarketplaceBookingStatus status,
                                               Pageable pageable);

    /**
     * Every marketplace order riding on one CRM booking.
     *
     * <p>Un-scoped by tenant on purpose — the only caller is the CRM-cancellation listener, which
     * already has the authoritative {@code tenantId} from the event and runs off the request thread.
     * It re-checks ownership before acting.</p>
     */
    List<PlatformHotelBooking> findByCrmBookingPublicIdAndDeletedAtIsNull(UUID crmBookingPublicId);

    /**
     * The idempotency probe. Per tenant, because two tenants may legitimately mint the same key.
     * A repeat submit returns the original request rather than a second CRM booking.
     */
    Optional<PlatformHotelBooking> findByTenantIdAndIdempotencyKeyAndDeletedAtIsNull(
            Long tenantId, String idempotencyKey);

    // ── SuperAdmin ──────────────────────────────────────────────────────────

    /**
     * Locks the row for the duration of a decision.
     *
     * <p>Approve and reject are guarded status transitions, and the guard is only meaningful if two
     * SuperAdmins cannot evaluate it at once. {@code @Version} alone would let both read REQUESTED,
     * both pass the check, and one lose at flush — after its side effects had already run.</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM PlatformHotelBooking b WHERE b.publicId = :publicId AND b.deletedAt IS NULL")
    Optional<PlatformHotelBooking> findByPublicIdForUpdate(@Param("publicId") UUID publicId);

    @Query("""
           SELECT b FROM PlatformHotelBooking b
           WHERE b.deletedAt IS NULL
             AND (:status IS NULL OR b.status = :status)
             AND (:tenantId IS NULL OR b.tenantId = :tenantId)
           ORDER BY b.id DESC
           """)
    Page<PlatformHotelBooking> searchForAdmin(@Param("status") MarketplaceBookingStatus status,
                                              @Param("tenantId") Long tenantId,
                                              Pageable pageable);

    long countByStatusAndDeletedAtIsNull(MarketplaceBookingStatus status);

    // ── Compensation ────────────────────────────────────────────────────────

    /**
     * Confirmed on the platform but not yet projected into the tenant's CRM. Drained by the sync
     * scheduler; ABANDONED rows are excluded so a poison pill stops consuming ticks.
     */
    @Query("""
           SELECT b FROM PlatformHotelBooking b
           WHERE b.deletedAt IS NULL
             AND b.status = com.crm.travelcrm.hotelmarketplace.booking.enums.MarketplaceBookingStatus.CONFIRMED
             AND b.crmSyncState IN :states
           ORDER BY b.id ASC
           """)
    List<PlatformHotelBooking> findAwaitingCrmSync(@Param("states") List<CrmSyncState> states, Pageable pageable);

    // ── Expiry ──────────────────────────────────────────────────────────────

    /**
     * Revised-price offers the tenant never answered.
     *
     * <p>Left open they would sit in the SuperAdmin queue as "waiting on the tenant" forever, and —
     * worse — could be accepted months later at a price the supplier withdrew long ago.</p>
     */
    @Query("""
           SELECT b FROM PlatformHotelBooking b
           WHERE b.deletedAt IS NULL
             AND b.status = com.crm.travelcrm.hotelmarketplace.booking.enums.MarketplaceBookingStatus.TENANT_APPROVAL_REQUIRED
             AND b.revisionExpiresAt IS NOT NULL
             AND b.revisionExpiresAt < :now
           ORDER BY b.id ASC
           """)
    List<PlatformHotelBooking> findExpiredRevisions(@Param("now") LocalDateTime now, Pageable pageable);
}
