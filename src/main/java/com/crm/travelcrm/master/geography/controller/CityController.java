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

@Slf4j
@RestController
@RequiredArgsConstructor
public class CityController {

    private final CityService cityService;

    // ------------------------------------------------------------------------
    // Flat City APIs
    // ------------------------------------------------------------------------

    @GetMapping({"/api/cities", "/api/v1/cities"})
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PagedApiResponse> getAll(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {

        return ResponseEntity.ok(cityService.getAll(page, size, sortBy, sortDir));
    }

    @PostMapping({"/api/cities", "/api/v1/cities"})
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN','MASTER_MANAGE')")
    public ResponseEntity<ApiResponse> create(
            @Valid @RequestBody CreateCityRequest request) {

        CityDto created = cityService.createFlat(request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("City created successfully", created, 201));
    }

    @GetMapping({"/api/cities/{cityId}", "/api/v1/cities/{cityId}"})
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse> getById(@PathVariable Long cityId) {

        return ResponseEntity.ok(
                ApiResponse.success("City fetched", cityService.getById(cityId)));
    }

    @PutMapping({"/api/cities/{cityId}", "/api/v1/cities/{cityId}"})
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN','MASTER_MANAGE')")
    public ResponseEntity<ApiResponse> update(
            @PathVariable Long cityId,
            @Valid @RequestBody UpdateCityRequest request) {

        return ResponseEntity.ok(
                ApiResponse.success(
                        "City updated successfully",
                        cityService.update(cityId, request)));
    }

    @DeleteMapping({"/api/cities/{cityId}", "/api/v1/cities/{cityId}"})
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN','MASTER_MANAGE')")
    public ResponseEntity<Void> delete(@PathVariable Long cityId) {

        cityService.delete(cityId);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------------
    // Filter APIs
    // ------------------------------------------------------------------------

    @GetMapping({
            "/api/cities/country/{countryId}",
            "/api/v1/countries/{countryId}/cities"
    })
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PagedApiResponse> getByCountry(
            @PathVariable Long countryId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {

        return ResponseEntity.ok(
                cityService.getByCountry(countryId, page, size, sortBy, sortDir));
    }

    @GetMapping({
            "/api/cities/destination/{destinationId}",
            "/api/v1/destinations/{destinationId}/cities"
    })
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PagedApiResponse> getByDestination(
            @PathVariable Long destinationId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {

        return ResponseEntity.ok(
                cityService.getByDestination(destinationId, page, size, sortBy, sortDir));
    }

    // ------------------------------------------------------------------------
    // Nested Create APIs
    // ------------------------------------------------------------------------

    @PostMapping("/api/v1/countries/{countryId}/cities")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN','MASTER_MANAGE')")
    public ResponseEntity<ApiResponse> createUnderCountry(
            @PathVariable Long countryId,
            @Valid @RequestBody CreateCityRequest request) {

        request.setCountryId(countryId);

        CityDto created = cityService.createFlat(request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("City created successfully", created, 201));
    }

    @PostMapping("/api/v1/destinations/{destinationId}/cities")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN','MASTER_MANAGE')")
    public ResponseEntity<ApiResponse> createUnderDestination(
            @PathVariable Long destinationId,
            @Valid @RequestBody CreateCityRequest request) {

        CityDto created = cityService.create(destinationId, request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("City created successfully", created, 201));
    }
}