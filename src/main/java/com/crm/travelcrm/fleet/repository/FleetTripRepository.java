package com.crm.travelcrm.fleet.repository;

import com.crm.travelcrm.fleet.entity.FleetTrip;
import com.crm.travelcrm.fleet.enums.FleetTripStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface FleetTripRepository
        extends JpaRepository<FleetTrip, Long>, JpaSpecificationExecutor<FleetTrip> {

    @EntityGraph(attributePaths = {"vehicle", "driver"})
    Optional<FleetTrip> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    /** Redeclared so list pages fetch vehicle+driver in one query (no N+1 from the mapper). */
    @Override
    @EntityGraph(attributePaths = {"vehicle", "driver"})
    Page<FleetTrip> findAll(Specification<FleetTrip> spec, Pageable pageable);

    @EntityGraph(attributePaths = {"vehicle", "driver"})
    List<FleetTrip> findByTenantIdAndStatusAndDeletedAtIsNullOrderByStartDatetimeAsc(
            Long tenantId, FleetTripStatus status);

    /** Planned trips starting within a window — dashboard "upcoming trips" list. */
    @EntityGraph(attributePaths = {"vehicle", "driver"})
    List<FleetTrip> findByTenantIdAndStatusAndStartDatetimeBetweenAndDeletedAtIsNullOrderByStartDatetimeAsc(
            Long tenantId, FleetTripStatus status, LocalDateTime from, LocalDateTime to);

    /** Single row [count, coalesce(sum(distanceKm),0)] for trips of {@code status} that ended in [from, to). */
    @Query("""
            select count(t), coalesce(sum(t.distanceKm), 0) from FleetTrip t
            where t.tenantId = :tenantId and t.status = :status and t.deletedAt is null
              and t.endDatetime >= :from and t.endDatetime < :to""")
    List<Object[]> countAndDistanceByStatusAndEndBetween(
            @Param("tenantId") Long tenantId, @Param("status") FleetTripStatus status,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    boolean existsByVehicle_IdAndStatusAndDeletedAtIsNull(Long vehicleId, FleetTripStatus status);

    boolean existsByDriver_IdAndStatusAndDeletedAtIsNull(Long driverId, FleetTripStatus status);

    /** Any active (non-trashed) trip rows — guards vehicle/driver deletion. */
    boolean existsByVehicle_IdAndDeletedAtIsNull(Long vehicleId);

    boolean existsByDriver_IdAndDeletedAtIsNull(Long driverId);

    @Query("""
            select count(distinct t.driver.id) from FleetTrip t
            where t.tenantId = :tenantId and t.status = :status and t.deletedAt is null""")
    long countDistinctDriversByStatus(
            @Param("tenantId") Long tenantId, @Param("status") FleetTripStatus status);

    /** Driver ids currently on an ongoing trip — batch source for the DTO {@code onTrip} flag. */
    @Query("""
            select distinct t.driver.id from FleetTrip t
            where t.tenantId = :tenantId and t.status = :status and t.deletedAt is null""")
    Set<Long> findDriverIdsByStatus(
            @Param("tenantId") Long tenantId, @Param("status") FleetTripStatus status);
}