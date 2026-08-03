package com.crm.travelcrm.fleet.repository;

import com.crm.travelcrm.fleet.entity.FleetCounterparty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FleetCounterpartyRepository extends JpaRepository<FleetCounterparty, Long> {

    Optional<FleetCounterparty> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    /** Tenant-scoped by-id resolve — never a bare {@code findById}, which bypasses the tenant filter. */
    Optional<FleetCounterparty> findByIdAndTenantId(Long id, Long tenantId);

    Page<FleetCounterparty> findByTenantIdAndDeletedAtIsNullAndNameContainingIgnoreCaseOrderByNameAsc(
            Long tenantId, String name, Pageable pageable);

    Page<FleetCounterparty> findByTenantIdAndDeletedAtIsNullOrderByNameAsc(Long tenantId, Pageable pageable);

    /** Dropdown source — active only, so a retired owner leaves the form but keeps its history. */
    List<FleetCounterparty> findByTenantIdAndActiveIsTrueAndDeletedAtIsNullOrderByNameAsc(Long tenantId);

    boolean existsByTenantIdAndNameIgnoreCaseAndDeletedAtIsNull(Long tenantId, String name);

    /**
     * Whether any vehicle still points at this party.
     *
     * <p>A cross-aggregate check written here rather than on the vehicle repository because
     * {@code FleetVehicle.vendorId} is a LOGICAL foreign key — the column holds a Vendor id in CRM
     * mode and a counterparty id in standalone mode, and there is no real constraint either way.
     * Deleting a party out from under a vehicle would leave its owner name pointing at nothing.
     */
    @org.springframework.data.jpa.repository.Query(
            "select count(v) from FleetVehicle v "
                    + "where v.tenantId = :tenantId and v.vendorId = :partyId and v.deletedAt is null")
    long countVehicles(
            @org.springframework.data.repository.query.Param("tenantId") Long tenantId,
            @org.springframework.data.repository.query.Param("partyId") Long partyId);
}
