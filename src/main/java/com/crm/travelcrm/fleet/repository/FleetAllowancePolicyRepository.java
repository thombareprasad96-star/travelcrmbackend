package com.crm.travelcrm.fleet.repository;

import com.crm.travelcrm.fleet.entity.FleetAllowancePolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FleetAllowancePolicyRepository extends JpaRepository<FleetAllowancePolicy, Long> {

    Optional<FleetAllowancePolicy> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    List<FleetAllowancePolicy> findByTenantIdAndDeletedAtIsNullOrderByEffectiveFromDesc(Long tenantId);

    /**
     * The policy in force for a trip: the most recent row effective on or before {@code onDate},
     * preferring one that matches the vehicle class over the tenant default.
     *
     * <p>Keyed on the trip's own date, never on today — raising the bata rate in October must not
     * restate what a driver was owed for a trip in June that was already settled and signed. The
     * {@code order by} puts a class-specific row ahead of the null-class default at the same date.
     */
    @Query("""
            select p from FleetAllowancePolicy p
            where p.tenantId = :tenantId and p.deletedAt is null
              and p.effectiveFrom <= :onDate
              and (p.vehicleClass is null or lower(p.vehicleClass) = lower(:vehicleClass))
            order by p.effectiveFrom desc,
                     case when p.vehicleClass is null then 1 else 0 end asc,
                     p.id desc""")
    List<FleetAllowancePolicy> findEffective(@Param("tenantId") Long tenantId,
                                             @Param("vehicleClass") String vehicleClass,
                                             @Param("onDate") LocalDate onDate);
}
