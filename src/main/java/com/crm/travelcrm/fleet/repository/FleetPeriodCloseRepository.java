package com.crm.travelcrm.fleet.repository;

import com.crm.travelcrm.fleet.entity.FleetPeriodClose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FleetPeriodCloseRepository extends JpaRepository<FleetPeriodClose, Long> {

    /**
     * Is this month locked right now? Checked on every financial write, so it is keyed on the two
     * columns the index covers and ignores rows that were reopened.
     */
    @Query("""
            select (count(p) > 0) from FleetPeriodClose p
            where p.tenantId = :tenantId and p.deletedAt is null
              and p.financialYear = :fy and p.periodMonth = :month
              and p.reopenedAt is null""")
    boolean isClosed(@Param("tenantId") Long tenantId,
                     @Param("fy") Integer financialYear,
                     @Param("month") Integer month);

    Optional<FleetPeriodClose> findByTenantIdAndFinancialYearAndPeriodMonthAndDeletedAtIsNull(
            Long tenantId, Integer financialYear, Integer periodMonth);

    /** Tenant-scoped by publicId — never resolve one of these with a bare findById. */
    Optional<FleetPeriodClose> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    List<FleetPeriodClose> findByTenantIdAndFinancialYearAndDeletedAtIsNullOrderByPeriodMonthAsc(
            Long tenantId, Integer financialYear);
}
