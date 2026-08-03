package com.crm.travelcrm.fleet.repository;

import com.crm.travelcrm.fleet.entity.FleetCashEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FleetCashEntryRepository extends JpaRepository<FleetCashEntry, Long> {

    @EntityGraph(attributePaths = {"driver", "trip"})
    Optional<FleetCashEntry> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    /** Every movement for one driver on one trip — the input to that driver's settlement. */
    @EntityGraph(attributePaths = {"driver", "trip"})
    List<FleetCashEntry> findByTrip_IdAndDriver_IdAndDeletedAtIsNullOrderByEntryDateAscIdAsc(
            Long tripId, Long driverId);

    @EntityGraph(attributePaths = {"driver", "trip"})
    Page<FleetCashEntry> findByTenantIdAndDriver_IdAndDeletedAtIsNullOrderByEntryDateDescIdDesc(
            Long tenantId, Long driverId, Pageable pageable);

    boolean existsByDriver_IdAndDeletedAtIsNull(Long driverId);

    boolean existsByReversalOf_IdAndDeletedAtIsNull(Long originalId);

    /**
     * A driver's running imprest balance across ALL trips — what he is holding of the company's money
     * right now. The owner's first question at 10pm ("how much cash is out, and with whom"), and the
     * reason cash movements are a ledger rather than a scalar on the driver row.
     *
     * <p>Note this is only the cash side; his spend against it lives in {@code fleet_expenses}. The
     * two are combined by {@code FleetSettlementCalculator}, which is the one place that arithmetic
     * exists.
     */
    @Query("""
            select coalesce(sum(c.baseAmount *
                case c.direction
                    when com.crm.travelcrm.fleet.enums.FleetCashDirection.ADVANCE_OUT then 1
                    when com.crm.travelcrm.fleet.enums.FleetCashDirection.CUSTOMER_COLLECTION then 1
                    when com.crm.travelcrm.fleet.enums.FleetCashDirection.RECOVERY then 1
                    when com.crm.travelcrm.fleet.enums.FleetCashDirection.ADJUSTMENT_DEBIT then 1
                    else -1
                end), 0)
            from FleetCashEntry c
            where c.tenantId = :tenantId and c.deletedAt is null and c.driver.id = :driverId""")
    BigDecimal sumSignedForDriver(@Param("tenantId") Long tenantId, @Param("driverId") Long driverId);
}
