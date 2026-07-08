package com.crm.travelcrm.fleet.repository;

import com.crm.travelcrm.fleet.entity.FleetMaintenanceLog;
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

public interface FleetMaintenanceLogRepository extends JpaRepository<FleetMaintenanceLog, Long> {

    @EntityGraph(attributePaths = "vehicle")
    Optional<FleetMaintenanceLog> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    @EntityGraph(attributePaths = "vehicle")
    Page<FleetMaintenanceLog> findByVehicle_PublicIdAndTenantIdAndDeletedAtIsNull(
            UUID vehiclePublicId, Long tenantId, Pageable pageable);

    /** Latest service record per vehicle — source of the denormalized next-service-due fields. */
    Optional<FleetMaintenanceLog> findFirstByVehicle_IdAndDeletedAtIsNullOrderByServiceDateDescIdDesc(Long vehicleId);

    /** Any active maintenance rows — guards vehicle deletion. */
    boolean existsByVehicle_IdAndDeletedAtIsNull(Long vehicleId);

    /** Total maintenance cost in [from, to) — dashboard month spend. Coalesced so it never returns null. */
    @Query("""
            select coalesce(sum(m.cost), 0) from FleetMaintenanceLog m
            where m.tenantId = :tenantId and m.deletedAt is null
              and m.serviceDate >= :from and m.serviceDate < :to""")
    BigDecimal sumCostBetween(@Param("tenantId") Long tenantId,
                              @Param("from") LocalDate from, @Param("to") LocalDate to);
}