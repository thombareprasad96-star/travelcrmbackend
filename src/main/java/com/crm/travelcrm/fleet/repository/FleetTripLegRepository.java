package com.crm.travelcrm.fleet.repository;

import com.crm.travelcrm.fleet.entity.FleetTripLeg;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FleetTripLegRepository extends JpaRepository<FleetTripLeg, Long> {

    @EntityGraph(attributePaths = {"vehicle", "driver"})
    List<FleetTripLeg> findByTrip_IdAndDeletedAtIsNullOrderBySeqAsc(Long tripId);

    @EntityGraph(attributePaths = {"vehicle", "driver", "trip"})
    Optional<FleetTripLeg> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    long countByTrip_IdAndDeletedAtIsNull(Long tripId);

    /** The running leg — the one with no end. At most one per trip. */
    Optional<FleetTripLeg> findFirstByTrip_IdAndEndDatetimeIsNullAndDeletedAtIsNullOrderBySeqDesc(Long tripId);

    /** Guards vehicle/driver deletion: an asset with leg history must never be trashed. */
    boolean existsByVehicle_IdAndDeletedAtIsNull(Long vehicleId);

    boolean existsByDriver_IdAndDeletedAtIsNull(Long driverId);
}
