package com.crm.travelcrm.fleet.repository;

import com.crm.travelcrm.fleet.entity.FleetExpense;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FleetExpenseRepository
        extends JpaRepository<FleetExpense, Long>, JpaSpecificationExecutor<FleetExpense> {

    @EntityGraph(attributePaths = {"vehicle", "trip", "driver"})
    Optional<FleetExpense> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    /** Internal-id resolve for attachment freeze checks — tenant-scoped, never bare findById. */
    Optional<FleetExpense> findByIdAndTenantId(Long id, Long tenantId);

    /**
     * Redeclared so the specification-driven list gets the same fetch plan — {@code @EntityGraph} on
     * the inherited {@code findAll(Specification, Pageable)} is the established N+1 fix in this module
     * (see {@code FleetTripRepository}). Without it every row triggers three lazy loads.
     */
    @Override
    @EntityGraph(attributePaths = {"vehicle", "trip", "driver"})
    Page<FleetExpense> findAll(org.springframework.data.jpa.domain.Specification<FleetExpense> spec,
                               Pageable pageable);

    /** Guards vehicle deletion — a vehicle carrying retained financial rows must never be trashed. */
    boolean existsByVehicle_IdAndDeletedAtIsNull(Long vehicleId);

    boolean existsByDriver_IdAndDeletedAtIsNull(Long driverId);

    boolean existsByTrip_IdAndDeletedAtIsNull(Long tripId);

    /** A row may be reversed at most once — belt to the partial unique index's braces. */
    boolean existsByReversalOf_IdAndDeletedAtIsNull(Long originalId);

    /** Every cost on one trip, in receipt order — the duty slip's "office use" block. */
    @EntityGraph(attributePaths = {"driver"})
    List<FleetExpense> findByTenantIdAndTrip_IdAndDeletedAtIsNullOrderByDocumentDateAscIdAsc(
            Long tenantId, Long tripId);

    /**
     * Driver-cash spend for one driver on one trip, in base currency.
     *
     * <p>The {@code isSystemComputed} exclusion is expressed here as an explicit type list rather
     * than a method call, because it must hold inside the database. Bata and night halt are paid out
     * of the advance the driver already holds and are discharged once through his entitlement;
     * counting them here as well double-subtracts and hands him money he was never owed.
     */
    @Query("""
            select coalesce(sum(e.baseAmount), 0) from FleetExpense e
            where e.tenantId = :tenantId and e.deletedAt is null
              and e.trip.id = :tripId and e.driver.id = :driverId
              and e.paidBy = com.crm.travelcrm.fleet.enums.FleetPaidBy.DRIVER_CASH
              and e.expenseType not in (
                    com.crm.travelcrm.fleet.enums.FleetExpenseType.DRIVER_BATA,
                    com.crm.travelcrm.fleet.enums.FleetExpenseType.NIGHT_HALT)""")
    BigDecimal sumDriverCashSpend(@Param("tenantId") Long tenantId,
                                  @Param("tripId") Long tripId,
                                  @Param("driverId") Long driverId);

    /**
     * The SAME rows {@link #sumDriverCashSpend} adds up, itemised for the printed settlement sheet.
     *
     * <p>The predicate below is a copy of that query's on purpose, and the two must be edited
     * together: a sheet whose printed lines do not add up to its printed total is the one document
     * a driver will refuse to sign — and he would be right to.
     */
    @Query("""
            select e from FleetExpense e
            where e.tenantId = :tenantId and e.deletedAt is null
              and e.trip.id = :tripId and e.driver.id = :driverId
              and e.paidBy = com.crm.travelcrm.fleet.enums.FleetPaidBy.DRIVER_CASH
              and e.expenseType not in (
                    com.crm.travelcrm.fleet.enums.FleetExpenseType.DRIVER_BATA,
                    com.crm.travelcrm.fleet.enums.FleetExpenseType.NIGHT_HALT)
            order by e.documentDate asc, e.id asc""")
    List<FleetExpense> findDriverCashSpend(@Param("tenantId") Long tenantId,
                                           @Param("tripId") Long tripId,
                                           @Param("driverId") Long driverId);

    /**
     * Total cost of a trip in base currency — the canonical aggregate: a naive sum over every
     * non-deleted row. Reversals net against their originals by arithmetic. Do NOT rewrite this as
     * "exclude rows that have been reversed": over a chain of an expense, its reversal and a reversal
     * of that reversal, the two forms disagree, and this one is the definition.
     */
    @Query("""
            select coalesce(sum(e.baseAmount), 0) from FleetExpense e
            where e.tenantId = :tenantId and e.deletedAt is null and e.trip.id = :tripId""")
    BigDecimal sumTripCost(@Param("tenantId") Long tenantId, @Param("tripId") Long tripId);
}
