package com.crm.travelcrm.master.dropdown;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.DropdownDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Unified dropdown endpoints for every master entity.
 *
 * <p>Base: {@code /api/masters/dropdown}</p>
 *
 * <pre>
 * ── Geography (cascading) ─────────────────────────────────────────────────────
 * GET /countries                     → all countries for the tenant
 * GET /destinations?countryId=       → active destinations under a country
 * GET /cities?destinationId=         → cities under a destination
 *
 * ── Hotel hierarchy ───────────────────────────────────────────────────────────
 * GET /hotels?destinationId=         → hotels (optional filter by destination)
 * GET /room-types?hotelId=           → room types under a hotel
 * GET /meal-plans?hotelId=           → meal plans under a hotel
 *
 * ── Other masters ─────────────────────────────────────────────────────────────
 * GET /sightseeings?destinationId=   → attractions (optional filter)
 * GET /vehicles                      → all vehicles visible to tenant
 * GET /addons                        → active addons
 * GET /airlines                      → all airlines
 * GET /cruises                       → all cruises
 * GET /cruise-room-types?cruiseId=   → cabin types under a cruise
 * </pre>
 *
 * All endpoints are read-only and available to any authenticated user.
 * Tenant scoping is automatic via the JWT — no tenantId is accepted from the client.
 */
@Slf4j
@RestController
@RequestMapping("/api/masters/dropdown")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MasterDropdownController {

    private final MasterDropdownService masterDropdownService;

    // ── Geography ─────────────────────────────────────────────────────────────

    @GetMapping("/countries")
    public ResponseEntity<ApiResponse<List<DropdownDto>>> getCountries() {
        return ok("Countries", masterDropdownService.getCountries());
    }

    /**
     * Destinations for a dropdown.
     * <ul>
     *   <li>{@code ?countryId=} → active destinations under that country</li>
     *   <li>no param           → ALL active destinations visible to the tenant
     *       (used by the lead itinerary, which needs no country pre-filter)</li>
     * </ul>
     */
    @GetMapping("/destinations")
    public ResponseEntity<ApiResponse<List<DropdownDto>>> getDestinations(
            @RequestParam(required = false) Long countryId) {
        return ok("Destinations", masterDropdownService.getDestinations(countryId));
    }

    /**
     * Cities for a dropdown. Supply exactly one filter:
     * <ul>
     *   <li>{@code ?destinationId=} → cities linked to that destination</li>
     *   <li>{@code ?countryId=}     → cities belonging directly to that country</li>
     * </ul>
     */
    @GetMapping("/cities")
    public ResponseEntity<ApiResponse<List<DropdownDto>>> getCities(
            @RequestParam(required = false) Long destinationId,
            @RequestParam(required = false) Long countryId) {
        if (destinationId != null) {
            return ok("Cities", masterDropdownService.getCities(destinationId));
        }
        if (countryId != null) {
            return ok("Cities", masterDropdownService.getCitiesByCountry(countryId));
        }
        throw new IllegalArgumentException("Provide either destinationId or countryId");
    }

    // ── Hotel hierarchy ───────────────────────────────────────────────────────

    @GetMapping("/hotels")
    public ResponseEntity<ApiResponse<List<DropdownDto>>> getHotels(
            @RequestParam(required = false) Long destinationId) {
        return ok("Hotels", masterDropdownService.getHotels(destinationId));
    }

    @GetMapping("/room-types")
    public ResponseEntity<ApiResponse<List<DropdownDto>>> getRoomTypes(
            @RequestParam Long hotelId) {
        return ok("Room types", masterDropdownService.getRoomTypes(hotelId));
    }

    @GetMapping("/meal-plans")
    public ResponseEntity<ApiResponse<List<DropdownDto>>> getMealPlans(
            @RequestParam Long hotelId) {
        return ok("Meal plans", masterDropdownService.getMealPlans(hotelId));
    }

    // ── Sightseeing ───────────────────────────────────────────────────────────

    @GetMapping("/sightseeings")
    public ResponseEntity<ApiResponse<List<DropdownDto>>> getSightseeings(
            @RequestParam(required = false) Long destinationId) {
        return ok("Sightseeings", masterDropdownService.getSightseeings(destinationId));
    }

    // ── Flat masters ──────────────────────────────────────────────────────────

    @GetMapping("/vehicles")
    public ResponseEntity<ApiResponse<List<DropdownDto>>> getVehicles() {
        return ok("Vehicles", masterDropdownService.getVehicles());
    }

    @GetMapping("/addons")
    public ResponseEntity<ApiResponse<List<DropdownDto>>> getAddons() {
        return ok("Addons", masterDropdownService.getAddons());
    }

    @GetMapping("/airlines")
    public ResponseEntity<ApiResponse<List<DropdownDto>>> getAirlines() {
        return ok("Airlines", masterDropdownService.getAirlines());
    }

    @GetMapping("/cruises")
    public ResponseEntity<ApiResponse<List<DropdownDto>>> getCruises() {
        return ok("Cruises", masterDropdownService.getCruises());
    }

    @GetMapping("/cruise-room-types")
    public ResponseEntity<ApiResponse<List<DropdownDto>>> getCruiseRoomTypes(
            @RequestParam Long cruiseId) {
        return ok("Cruise room types", masterDropdownService.getCruiseRoomTypes(cruiseId));
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    private static ResponseEntity<ApiResponse<List<DropdownDto>>> ok(String entity, List<DropdownDto> data) {
        return ResponseEntity.ok(ApiResponse.success(entity + " fetched successfully", data));
    }
}