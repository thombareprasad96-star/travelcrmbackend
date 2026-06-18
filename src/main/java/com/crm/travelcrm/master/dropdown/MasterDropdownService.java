package com.crm.travelcrm.master.dropdown;

import com.crm.travelcrm.common.dto.DropdownDto;

import java.util.List;

/**
 * Single entry-point for every master dropdown in the CRM.
 *
 * <p>All methods are tenant-scoped via
 * {@link com.crm.travelcrm.common.context.TenantContext} — no tenantId is
 * accepted from the caller. Optional parent-filter parameters (countryId,
 * destinationId, …) narrow the result to relevant children only.</p>
 *
 * <h3>Cascade hierarchy</h3>
 * <pre>
 * Country
 *   └─ Destination  (countryId)
 *        └─ City    (destinationId)
 *             ├─ Hotel          (destinationId)
 *             │    ├─ RoomType  (hotelId)
 *             │    └─ MealPlan  (hotelId)
 *             ├─ Sightseeing    (destinationId)
 *             ├─ Vehicle        (flat)
 *             ├─ Addon          (flat, active only)
 *             ├─ Airline        (flat)
 *             └─ Cruise         (flat)
 *                  └─ CruiseRoomType (cruiseId)
 * </pre>
 */
public interface MasterDropdownService {

    // ── Geography ─────────────────────────────────────────────────────────────

    List<DropdownDto> getCountries();

    /** Active destinations visible to the tenant under the given country. */
    List<DropdownDto> getDestinations(Long countryId);

    /** Cities under the given destination. */
    List<DropdownDto> getCities(Long destinationId);

    // ── Hotel hierarchy ───────────────────────────────────────────────────────

    /**
     * Hotels under a destination.
     * Pass {@code destinationId = null} to get all hotels for the tenant.
     */
    List<DropdownDto> getHotels(Long destinationId);

    List<DropdownDto> getRoomTypes(Long hotelId);

    List<DropdownDto> getMealPlans(Long hotelId);

    // ── Other masters ─────────────────────────────────────────────────────────

    /**
     * Sightseeing attractions.
     * Pass {@code destinationId = null} to get all for the tenant.
     */
    List<DropdownDto> getSightseeings(Long destinationId);

    /** All vehicles visible to the tenant (tenant-owned + platform-global). */
    List<DropdownDto> getVehicles();

    /** All active addons for the tenant. */
    List<DropdownDto> getAddons();

    /** All airlines for the tenant. */
    List<DropdownDto> getAirlines();

    /** All cruises for the tenant. */
    List<DropdownDto> getCruises();

    List<DropdownDto> getCruiseRoomTypes(Long cruiseId);
}