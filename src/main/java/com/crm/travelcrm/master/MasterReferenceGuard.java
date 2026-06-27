package com.crm.travelcrm.master;

import com.crm.travelcrm.booking.repository.BookingRepository;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.master.addon.AddonRepository;
import com.crm.travelcrm.master.airline.AirlineRepository;
import com.crm.travelcrm.master.cruise.CruiseRepository;
import com.crm.travelcrm.master.geography.repository.CityRepository;
import com.crm.travelcrm.master.geography.repository.DestinationRepository;
import com.crm.travelcrm.master.hotel.HotelRepository;
import com.crm.travelcrm.master.sightseeing.SightseeingRepository;
import com.crm.travelcrm.master.vehicle.VehicleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Referential-integrity guard for soft-deleting master data. Before a master record is moved to
 * Trash, this blocks the delete (409 "in use") if any <b>active (non-trashed)</b> record still
 * references it, so we never strand a live record behind a trashed master.
 *
 * <h3>What actually references master data in this codebase</h3>
 * <ul>
 *   <li><b>Geography hierarchy</b> — real DB foreign keys: Country ← Destination, Country/Destination
 *       ← City, and City ← Hotel / Sightseeing / Airline / Cruise / Add-on / Vehicle. These are the
 *       checks below.</li>
 *   <li><b>Booking → Destination</b> — {@code Booking.destinationId} is a logical FK; an active
 *       booking blocks deleting that destination.</li>
 *   <li><b>Bookings / Quotations → leaf masters</b> (Hotel, Vehicle, Airline, Cruise, Add-on,
 *       Sightseeing) are stored as <b>name snapshots</b>, never FKs. Deleting such a master can never
 *       orphan or break a booking/quotation — the snapshot keeps rendering — so there is deliberately
 *       no "in use" check for them: they soft-delete to Trash freely and stay restorable. The same is
 *       true of Vendors (referenced by nothing).</li>
 * </ul>
 *
 * <p>The existence checks run inside the master service's {@code @Transactional} delete, where
 * {@code softDeleteFilter} is active, so they naturally see only non-trashed children. Combined with
 * the children-before-parents purge order, a parent can only be trashed once its children already
 * are — which keeps the 30-day hard purge free of foreign-key violations.</p>
 */
@Component
@RequiredArgsConstructor
public class MasterReferenceGuard {

    private final BookingRepository bookingRepository;
    private final DestinationRepository destinationRepository;
    private final CityRepository cityRepository;
    private final HotelRepository hotelRepository;
    private final AirlineRepository airlineRepository;
    private final CruiseRepository cruiseRepository;
    private final AddonRepository addonRepository;
    private final SightseeingRepository sightseeingRepository;
    private final VehicleRepository vehicleRepository;

    /** A country can be trashed only when no active destination or city sits under it. */
    public void assertCountryDeletable(Long countryId, Long tenantId) {
        if (destinationRepository.existsByTenantIdAndCountryId(tenantId, countryId)
                || cityRepository.existsByTenantIdAndCountryId(tenantId, countryId)) {
            throw inUse("country", "destinations or cities");
        }
    }

    /**
     * A destination can be trashed only when no active booking points at it. Cities are an optional
     * link ({@code destination_id} is nullable) and are detached by the service, so they don't block.
     */
    public void assertDestinationDeletable(Long destinationId, Long tenantId) {
        if (bookingRepository.existsByDestinationIdAndTenantIdAndDeletedAtIsNull(destinationId, tenantId)) {
            throw inUse("destination", "bookings");
        }
    }

    /** A city can be trashed only when no active hotel, sightseeing, transport or add-on uses it. */
    public void assertCityDeletable(Long cityId, Long tenantId) {
        boolean referenced = hotelRepository.existsByTenantIdAndCity_Id(tenantId, cityId)
                || sightseeingRepository.existsByTenantIdAndCity_Id(tenantId, cityId)
                || airlineRepository.existsByTenantIdAndCity_Id(tenantId, cityId)
                || cruiseRepository.existsByTenantIdAndCity_Id(tenantId, cityId)
                || addonRepository.existsByTenantIdAndCity_Id(tenantId, cityId)
                || vehicleRepository.existsByTenantIdAndCity_Id(tenantId, cityId);
        if (referenced) {
            throw inUse("city", "hotels, sightseeing, transport or add-ons");
        }
    }

    private BusinessException inUse(String what, String dependents) {
        return new BusinessException(
                "This " + what + " is still in use by active " + dependents
                        + " and cannot be deleted. Remove or reassign them first.",
                HttpStatus.CONFLICT);
    }
}
