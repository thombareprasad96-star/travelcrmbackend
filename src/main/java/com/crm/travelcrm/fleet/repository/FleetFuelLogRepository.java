package com.crm.travelcrm.fleet.repository;

import com.crm.travelcrm.fleet.entity.FleetFuelLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

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
}