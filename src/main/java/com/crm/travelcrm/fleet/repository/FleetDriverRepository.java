package com.crm.travelcrm.fleet.repository;

import com.crm.travelcrm.fleet.entity.FleetDriver;
import com.crm.travelcrm.fleet.enums.FleetDriverStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FleetDriverRepository
        extends JpaRepository<FleetDriver, Long>, JpaSpecificationExecutor<FleetDriver> {

    Optional<FleetDriver> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    /** status → count in one query — dashboard tiles. */
    @Query("""
            select d.status, count(d) from FleetDriver d
            where d.tenantId = :tenantId and d.deletedAt is null
            group by d.status""")
    List<Object[]> countGroupedByStatus(@Param("tenantId") Long tenantId);

    List<FleetDriver> findByTenantIdAndDeletedAtIsNullOrderByNameAsc(Long tenantId);

    List<FleetDriver> findByTenantIdAndStatusAndDeletedAtIsNullOrderByNameAsc(
            Long tenantId, FleetDriverStatus status);

    /** Active drivers whose licence expires on/before {@code limit} — feeds the expiry scan + dashboard. */
    List<FleetDriver> findByTenantIdAndStatusAndLicenseExpiryLessThanEqualAndDeletedAtIsNull(
            Long tenantId, FleetDriverStatus status, LocalDate limit);
}