package com.crm.travelcrm.fleet.integration.adapter.crm;

import com.crm.travelcrm.vendor.entity.Vendor;
import com.crm.travelcrm.vendor.enums.VendorStatus;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Fleet-local, read-only Vendor lookup used only when (re)linking a vendor-owned vehicle —
 * a single-row read at create/update time; list views read the snapshotted
 * {@code FleetVehicle.vendorName} instead of joining the 3-table Vendor mapping.
 */
public interface FleetVendorLookupRepository extends Repository<Vendor, Long> {

    Optional<Vendor> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    /** Slim projection for the vehicle-form vendor dropdown — avoids the 3-table entity load. */
    interface VendorOptionView {
        UUID getPublicId();
        String getVendorName();
    }

    @Query("""
            select v.publicId as publicId, v.vendorName as vendorName from Vendor v
            where v.tenantId = :tenantId and v.status = :status and v.deletedAt is null
            order by v.vendorName asc""")
    List<VendorOptionView> findOptions(
            @Param("tenantId") Long tenantId, @Param("status") VendorStatus status);
}