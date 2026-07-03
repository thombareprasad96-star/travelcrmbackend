package com.crm.travelcrm.fleet.repository;

import com.crm.travelcrm.fleet.entity.FleetVehicle;
import com.crm.travelcrm.fleet.enums.FleetVehicleStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FleetVehicleRepository
        extends JpaRepository<FleetVehicle, Long>, JpaSpecificationExecutor<FleetVehicle> {

    Optional<FleetVehicle> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    boolean existsByTenantIdAndVehicleNumberIgnoreCaseAndDeletedAtIsNull(Long tenantId, String vehicleNumber);

    boolean existsByTenantIdAndVehicleNumberIgnoreCaseAndDeletedAtIsNullAndIdNot(
            Long tenantId, String vehicleNumber, Long id);

    /** Trashed row with the same number — lets create offer "restore instead of duplicate". */
    Optional<FleetVehicle> findFirstByTenantIdAndVehicleNumberIgnoreCaseAndDeletedAtIsNotNull(
            Long tenantId, String vehicleNumber);

    /** status → count in one query — dashboard tiles. */
    @Query("""
            select v.status, count(v) from FleetVehicle v
            where v.tenantId = :tenantId and v.deletedAt is null
            group by v.status""")
    List<Object[]> countGroupedByStatus(@Param("tenantId") Long tenantId);

    List<FleetVehicle> findByTenantIdAndDeletedAtIsNullOrderByVehicleNumberAsc(Long tenantId);

    List<FleetVehicle> findByTenantIdAndStatusAndDeletedAtIsNullOrderByVehicleNumberAsc(
            Long tenantId, FleetVehicleStatus status);

    /** Vehicles with ANY of the four documents expiring on/before {@code limit} (includes already-expired). */
    @Query("""
            select v from FleetVehicle v
            where v.tenantId = :tenantId and v.deletedAt is null
              and (v.insuranceExpiry <= :limit or v.rcExpiry <= :limit
                or v.permitExpiry <= :limit or v.pucExpiry <= :limit)""")
    List<FleetVehicle> findWithDocumentsExpiringBy(
            @Param("tenantId") Long tenantId, @Param("limit") LocalDate limit);

    /** Vehicles due for service by date (≤ dateLimit) or by odometer (within kmThreshold of due km). */
    @Query("""
            select v from FleetVehicle v
            where v.tenantId = :tenantId and v.deletedAt is null
              and ((v.nextServiceDueDate is not null and v.nextServiceDueDate <= :dateLimit)
                or (v.nextServiceDueKm is not null and v.lastOdometer is not null
                    and (v.nextServiceDueKm - v.lastOdometer) <= :kmThreshold))""")
    List<FleetVehicle> findServiceDue(
            @Param("tenantId") Long tenantId,
            @Param("dateLimit") LocalDate dateLimit,
            @Param("kmThreshold") int kmThreshold);
}
