package com.crm.travelcrm.fleet.repository;

import com.crm.travelcrm.fleet.entity.FleetComplianceDocument;
import com.crm.travelcrm.fleet.enums.FleetDocumentCategory;
import com.crm.travelcrm.fleet.enums.FleetDocumentStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FleetComplianceDocumentRepository
        extends JpaRepository<FleetComplianceDocument, Long>,
                JpaSpecificationExecutor<FleetComplianceDocument> {

    @EntityGraph(attributePaths = {"vehicle", "driver", "supersedes"})
    Optional<FleetComplianceDocument> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    /** Full history for one asset, newest first — the renewal chain, superseded rows included. */
    @EntityGraph(attributePaths = {"vehicle", "driver"})
    List<FleetComplianceDocument> findByTenantIdAndVehicle_PublicIdAndDeletedAtIsNullOrderByValidUntilDesc(
            Long tenantId, UUID vehiclePublicId);

    @EntityGraph(attributePaths = {"vehicle", "driver"})
    List<FleetComplianceDocument> findByTenantIdAndDriver_PublicIdAndDeletedAtIsNullOrderByValidUntilDesc(
            Long tenantId, UUID driverPublicId);

    /**
     * The CURRENT paper of a category for a vehicle — the one a compliance check reads.
     *
     * <p>Superseded and revoked rows are excluded here on purpose: they are history, not standing.
     * Ordered by validity so that if two active rows somehow exist (a national and a state permit
     * both recorded as NATIONAL, say) the one valid longest wins rather than an arbitrary row.
     */
    @Query("""
            select d from FleetComplianceDocument d
            where d.tenantId = :tenantId and d.deletedAt is null
              and d.vehicle.id = :vehicleId and d.category = :category
              and d.status not in (com.crm.travelcrm.fleet.enums.FleetDocumentStatus.SUPERSEDED,
                                   com.crm.travelcrm.fleet.enums.FleetDocumentStatus.REVOKED)
            order by d.validUntil desc nulls first, d.id desc""")
    List<FleetComplianceDocument> findCurrentForVehicle(@Param("tenantId") Long tenantId,
                                                        @Param("vehicleId") Long vehicleId,
                                                        @Param("category") FleetDocumentCategory category);

    /** Every current paper for a vehicle — the assignment check needs all of them at once. */
    @Query("""
            select d from FleetComplianceDocument d
            where d.tenantId = :tenantId and d.deletedAt is null and d.vehicle.id = :vehicleId
              and d.status not in (com.crm.travelcrm.fleet.enums.FleetDocumentStatus.SUPERSEDED,
                                   com.crm.travelcrm.fleet.enums.FleetDocumentStatus.REVOKED)""")
    List<FleetComplianceDocument> findCurrentForVehicle(@Param("tenantId") Long tenantId,
                                                        @Param("vehicleId") Long vehicleId);

    @Query("""
            select d from FleetComplianceDocument d
            where d.tenantId = :tenantId and d.deletedAt is null and d.driver.id = :driverId
              and d.status not in (com.crm.travelcrm.fleet.enums.FleetDocumentStatus.SUPERSEDED,
                                   com.crm.travelcrm.fleet.enums.FleetDocumentStatus.REVOKED)""")
    List<FleetComplianceDocument> findCurrentForDriver(@Param("tenantId") Long tenantId,
                                                       @Param("driverId") Long driverId);

    /**
     * Everything lapsing on or before {@code limit}, for the dashboard and the expiry scan.
     * Superseded/revoked rows are excluded — a replaced certificate is not an alert.
     */
    @EntityGraph(attributePaths = {"vehicle", "driver"})
    @Query("""
            select d from FleetComplianceDocument d
            where d.tenantId = :tenantId and d.deletedAt is null
              and d.validUntil is not null and d.validUntil <= :limit
              and d.status not in (com.crm.travelcrm.fleet.enums.FleetDocumentStatus.SUPERSEDED,
                                   com.crm.travelcrm.fleet.enums.FleetDocumentStatus.REVOKED)
            order by d.validUntil asc""")
    List<FleetComplianceDocument> findExpiringBy(@Param("tenantId") Long tenantId,
                                                 @Param("limit") LocalDate limit);

    /** Nepal entries whose exit deadline is approaching — overstaying is a fine at the border. */
    @EntityGraph(attributePaths = {"vehicle"})
    @Query("""
            select d from FleetComplianceDocument d
            where d.tenantId = :tenantId and d.deletedAt is null
              and d.exitDeadline is not null and d.exitDeadline <= :limit
              and d.status not in (com.crm.travelcrm.fleet.enums.FleetDocumentStatus.SUPERSEDED,
                                   com.crm.travelcrm.fleet.enums.FleetDocumentStatus.REVOKED)
            order by d.exitDeadline asc""")
    List<FleetComplianceDocument> findExitDeadlinesBy(@Param("tenantId") Long tenantId,
                                                      @Param("limit") LocalDate limit);

    /**
     * Guards asset deletion. A vehicle or driver carrying statutory records must never be trashed —
     * the 30-day purge would hard-delete them through the FK, and the retention requirement is eight
     * years.
     */
    boolean existsByVehicle_IdAndDeletedAtIsNull(Long vehicleId);

    boolean existsByDriver_IdAndDeletedAtIsNull(Long driverId);

    long countByTenantIdAndStatusAndDeletedAtIsNull(Long tenantId, FleetDocumentStatus status);
}
