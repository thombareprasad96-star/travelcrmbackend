package com.crm.travelcrm.fleet.integration.adapter.crm;

import com.crm.travelcrm.fleet.integration.spi.FleetParty;
import com.crm.travelcrm.fleet.integration.spi.FleetPartyPort;
import com.crm.travelcrm.vendor.entity.Vendor;
import com.crm.travelcrm.vendor.enums.VendorStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * CRM-suite adapter: a vehicle's owner is a {@code Vendor} from the shared vendor master.
 *
 * <p>The only place in the fleet module allowed to name {@code vendor.entity.Vendor}. Identical
 * behaviour to the previous inline {@code applyVendor} / {@code vendorOptions} — including the slim
 * projection for the dropdown, which exists because {@code Vendor} maps across three tables via
 * {@code @SecondaryTable} and loading whole entities for a picker is wasteful.
 *
 * <p>{@link #options} deliberately lists ACTIVE vendors only: a blacklisted or suspended supplier
 * should not be offered as the owner of a vehicle you are about to put on the road.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.product-mode", havingValue = "CRM_SUITE", matchIfMissing = true)
public class CrmVendorPartyAdapter implements FleetPartyPort {

    private final FleetVendorLookupRepository vendorLookupRepository;

    @Override
    public Optional<FleetParty> resolve(UUID publicId, Long tenantId) {
        return vendorLookupRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .map(this::snapshot);
    }

    @Override
    public List<FleetParty> options(Long tenantId) {
        return vendorLookupRepository.findOptions(tenantId, VendorStatus.ACTIVE)
                .stream()
                .map(v -> new FleetParty(null, v.getPublicId(), v.getVendorName()))
                .toList();
    }

    @Override
    public boolean supportsDirectory() {
        return true;
    }

    private FleetParty snapshot(Vendor vendor) {
        return new FleetParty(vendor.getId(), vendor.getPublicId(), vendor.getVendorName());
    }
}
