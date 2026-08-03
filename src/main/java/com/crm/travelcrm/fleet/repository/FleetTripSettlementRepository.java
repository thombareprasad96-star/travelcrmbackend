package com.crm.travelcrm.fleet.repository;

import com.crm.travelcrm.fleet.entity.FleetTripSettlement;
import com.crm.travelcrm.fleet.enums.FleetSettlementStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FleetTripSettlementRepository extends JpaRepository<FleetTripSettlement, Long> {

    /** One settlement per (trip, driver) — a multi-driver trip has one per man. */
    @EntityGraph(attributePaths = {"trip", "driver"})
    Optional<FleetTripSettlement> findByTrip_IdAndDriver_IdAndDeletedAtIsNull(Long tripId, Long driverId);

    @EntityGraph(attributePaths = {"trip", "driver"})
    Optional<FleetTripSettlement> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    /** Internal-id resolve for attachment freeze checks — tenant-scoped, never bare findById. */
    Optional<FleetTripSettlement> findByIdAndTenantId(Long id, Long tenantId);

    @EntityGraph(attributePaths = {"trip", "driver"})
    List<FleetTripSettlement> findByTrip_IdAndDeletedAtIsNullOrderByIdAsc(Long tripId);

    /**
     * Takes the settlement row as a mutex before any financial write on that trip.
     *
     * <p><b>Why a pessimistic lock and not {@code @Version}.</b> Optimistic locking only fires when
     * two writers touch the SAME row. The race that loses money touches two: a settle call reads the
     * expenses, sums 5,000 and computes what the driver owes, while a clerk INSERTS a Rs 900 toll —
     * a different table entirely. Neither row version is contended, no exception fires, and the
     * driver goes home 900 short on a signed sheet. Serialising every write behind this row closes
     * it, and also makes the {@code isMutable()} status check safe, which is otherwise an unguarded
     * read-then-write.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from FleetTripSettlement s
            where s.trip.id = :tripId and s.driver.id = :driverId and s.deletedAt is null""")
    Optional<FleetTripSettlement> lockFor(@Param("tripId") Long tripId, @Param("driverId") Long driverId);

    /** The owner's 10pm screen: whose cash is still out, and which trips are not squared. */
    @EntityGraph(attributePaths = {"trip", "driver"})
    List<FleetTripSettlement> findByTenantIdAndStatusInAndDeletedAtIsNullOrderByIdDesc(
            Long tenantId, List<FleetSettlementStatus> statuses);

    /** Trips that reported as squared but have moved since — late bills, reversals. */
    List<FleetTripSettlement> findByTenantIdAndHasPostSettlementMovementIsTrueAndDeletedAtIsNull(Long tenantId);

    /**
     * Sheets in a period that are still open — the reason a period close can be refused.
     *
     * <p>Closing a month whose driver cash is unsquared is a trap with no way out: the lock stops
     * every further entry, so the return that would have squared it can never be recorded. The money
     * is then permanently unaccounted, and the only escape is reopening a filed period.
     *
     * <p>Scoped by the TRIP's start date rather than the settlement's own — a settlement has no date
     * of its own, and the duty is what belongs to the month.
     */
    @Query("""
            select count(s) from FleetTripSettlement s
            where s.tenantId = :tenantId and s.deletedAt is null
              and s.status in (com.crm.travelcrm.fleet.enums.FleetSettlementStatus.OPEN,
                               com.crm.travelcrm.fleet.enums.FleetSettlementStatus.RECONCILED)
              and s.trip.startDatetime >= :from and s.trip.startDatetime < :to""")
    long countUnsettledInPeriod(@Param("tenantId") Long tenantId,
                                @Param("from") java.time.LocalDateTime from,
                                @Param("to") java.time.LocalDateTime to);

    /**
     * Flip every signed sheet in a period to the target status. This is what makes
     * {@code FleetSettlementStatus.LOCKED} reachable at all — {@code settle()} stops at SETTLED, and
     * the period close is what freezes it beyond correction.
     */
    @Modifying
    @Query("""
            update FleetTripSettlement s set s.status = :target
            where s.tenantId = :tenantId and s.deletedAt is null and s.status = :current
              and s.trip.id in (select t.id from FleetTrip t
                                where t.tenantId = :tenantId
                                  and t.startDatetime >= :from and t.startDatetime < :to)""")
    int reStatusInPeriod(@Param("tenantId") Long tenantId,
                         @Param("current") FleetSettlementStatus current,
                         @Param("target") FleetSettlementStatus target,
                         @Param("from") java.time.LocalDateTime from,
                         @Param("to") java.time.LocalDateTime to);
}
