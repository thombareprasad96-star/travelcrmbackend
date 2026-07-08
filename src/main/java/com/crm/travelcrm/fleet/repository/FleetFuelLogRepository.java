package com.crm.travelcrm.fleet.repository;

import com.crm.travelcrm.fleet.entity.FleetFuelLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface FleetFuelLogRepository extends JpaRepository<FleetFuelLog, Long> {

    @EntityGraph(attributePaths = "vehicle")
    Optional<FleetFuelLog> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    @EntityGraph(attributePaths = "vehicle")
    Page<FleetFuelLog> findByVehicle_PublicIdAndTenantIdAndDeletedAtIsNull(
            UUID vehiclePublicId, Long tenantId, Pageable pageable);

    /** Any active fuel rows — guards vehicle deletion. */
    boolean existsByVehicle_IdAndDeletedAtIsNull(Long vehicleId);

    /** Total fuel cost in [from, to) — dashboard month spend. Coalesced so it never returns null. */
    @Query("""
            select coalesce(sum(f.cost), 0) from FleetFuelLog f
            where f.tenantId = :tenantId and f.deletedAt is null
              and f.date >= :from and f.date < :to""")
    BigDecimal sumCostBetween(@Param("tenantId") Long tenantId,
                              @Param("from") LocalDate from, @Param("to") LocalDate to);
}