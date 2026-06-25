package com.crm.travelcrm.master.geography.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.master.geography.dto.request.CreateCityRequest;
import com.crm.travelcrm.master.geography.dto.request.UpdateCityRequest;
import com.crm.travelcrm.master.geography.dto.response.CityDto;
import com.crm.travelcrm.master.geography.service.CityService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * City master endpoints. Collection routes are nested under a destination; item
 * routes are flat ({@code /cities/{id}}) per the spec.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class CityController {

    private final CityService cityService;

    // ── Flat endpoints (/api/cities) — used by the frontend City master page ──

    @GetMapping("/api/cities")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PagedApiResponse<CityDto>> getAll(
            @RequestParam(defaultValue = "0")         int page,
            @RequestParam(defaultValue = "100")       int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc")      String sortDir) {
        return ResponseEntity.ok(cityService.getAll(page, size, sortBy, sortDir));
    }

    @PostMapping("/api/cities")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')")
    public ResponseEntity<ApiResponse<CityDto>> createFlat(
            @Valid @RequestBody CreateCityRequest request) {
        CityDto created = cityService.createFlat(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("City created successfully", created, 201));
    }

    @PutMapping("/api/cities/{cityId}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')")
    public ResponseEntity<ApiResponse<CityDto>> updateFlat(
            @PathVariable Long cityId,
            @Valid @RequestBody UpdateCityRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success("City updated successfully", cityService.update(cityId, request)));
    }

    @DeleteMapping("/api/cities/{cityId}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')")
    public ResponseEntity<Void> deleteFlat(@PathVariable Long cityId) {
        cityService.delete(cityId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/cities/{cityId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<CityDto>> getByIdFlat(@PathVariable Long cityId) {
        return ResponseEntity.ok(ApiResponse.success("City fetched", cityService.getById(cityId)));
    }

    // ── Nested endpoints by country (/api/v1/countries/{id}/cities) ───────────

    @GetMapping("/api/v1/countries/{countryId}/cities")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PagedApiResponse<CityDto>> getByCountry(
            @PathVariable Long countryId,
            @RequestParam(defaultValue = "0")         int page,
            @RequestParam(defaultValue = "20")        int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc")      String sortDir) {
        return ResponseEntity.ok(
                cityService.getByCountry(countryId, page, size, sortBy, sortDir));
    }

    @PostMapping("/api/v1/countries/{countryId}/cities")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')")
    public ResponseEntity<ApiResponse<CityDto>> createUnderCountry(
            @PathVariable Long countryId,
            @Valid @RequestBody CreateCityRequest request) {
        request.setCountryId(countryId);   // path wins over body
        CityDto created = cityService.createFlat(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("City created successfully", created, 201));
    }

    // ── Nested endpoints by destination (/api/v1/destinations/{id}/cities) ────

    @GetMapping("/api/v1/destinations/{destinationId}/cities")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PagedApiResponse<CityDto>> getByDestination(
            @PathVariable Long destinationId,
            @RequestParam(defaultValue = "0")         int page,
            @RequestParam(defaultValue = "20")        int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc")      String sortDir) {
        return ResponseEntity.ok(
                cityService.getByDestination(destinationId, page, size, sortBy, sortDir));
    }

    @PostMapping("/api/v1/destinations/{destinationId}/cities")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')")
    public ResponseEntity<ApiResponse<CityDto>> create(
            @PathVariable Long destinationId,
            @Valid @RequestBody CreateCityRequest request) {
        CityDto created = cityService.create(destinationId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("City created successfully", created, 201));
    }

    @GetMapping("/api/v1/cities/{cityId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<CityDto>> getById(@PathVariable Long cityId) {
        return ResponseEntity.ok(ApiResponse.success("City fetched", cityService.getById(cityId)));
    }

    @PutMapping("/api/v1/cities/{cityId}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')")
    public ResponseEntity<ApiResponse<CityDto>> update(
            @PathVariable Long cityId,
            @Valid @RequestBody UpdateCityRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success("City updated successfully", cityService.update(cityId, request)));
    }

    @DeleteMapping("/api/v1/cities/{cityId}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'MASTER_MANAGE')")
    public ResponseEntity<Void> delete(@PathVariable Long cityId) {
        cityService.delete(cityId);
        return ResponseEntity.noContent().build();
    }
}