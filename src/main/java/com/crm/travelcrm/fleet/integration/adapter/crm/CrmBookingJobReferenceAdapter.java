package com.crm.travelcrm.fleet.integration.adapter.crm;

import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.fleet.integration.spi.FleetJobReference;
import com.crm.travelcrm.fleet.integration.spi.FleetJobReferencePort;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * CRM-suite adapter: a fleet trip's job is a {@code Booking}.
 *
 * <p>This is the ONLY place in the fleet module allowed to name {@code booking.entity.Booking}, and
 * {@code FleetBoundaryArchTest} enforces that. The behaviour is byte-for-byte what
 * {@code FleetTripServiceImpl.applyBooking} did inline before the extraction — same tenant-scoped,
 * soft-delete-aware finder, same three snapshotted values — so no CRM tenant sees any change.
 *
 * <p><b>Active by default.</b> {@code matchIfMissing = true} means an existing deployment that has
 * never heard of {@code app.product-mode} keeps full booking integration with no configuration.
 * Only an explicit {@code app.product-mode=FLEET_STANDALONE} switches it off.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.product-mode", havingValue = "CRM_SUITE", matchIfMissing = true)
public class CrmBookingJobReferenceAdapter implements FleetJobReferencePort {

    private final FleetBookingLookupRepository bookingLookupRepository;

    @Override
    public Optional<FleetJobReference> resolve(UUID publicId, Long tenantId) {
        return bookingLookupRepository
                .findByPublicIdAndTenantIdAndDeletedAtIsNull(publicId, tenantId)
                .map(this::snapshot);
    }

    @Override
    public boolean supportsLookup() {
        return true;
    }

    private FleetJobReference snapshot(Booking booking) {
        return new FleetJobReference(booking.getId(), booking.getPublicId(), booking.getBookingCode());
    }
}
