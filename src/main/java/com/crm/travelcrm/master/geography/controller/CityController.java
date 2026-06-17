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
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CityController {

    private final CityService cityService;

    @GetMapping("/destinations/{destinationId}/cities")
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

    @PostMapping("/destinations/{destinationId}/cities")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<ApiResponse<CityDto>> create(
            @PathVariable Long destinationId,
            @Valid @RequestBody CreateCityRequest request) {
        CityDto created = cityService.create(destinationId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("City created successfully", created, 201));
    }

    @GetMapping("/cities/{cityId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<CityDto>> getById(@PathVariable Long cityId) {
        return ResponseEntity.ok(ApiResponse.success("City fetched", cityService.getById(cityId)));
    }

    @PutMapping("/cities/{cityId}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<ApiResponse<CityDto>> update(
            @PathVariable Long cityId,
            @Valid @RequestBody UpdateCityRequest request) {
        return ResponseEntity.ok(
                ApiResponse.success("City updated successfully", cityService.update(cityId, request)));
    }

    @DeleteMapping("/cities/{cityId}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<Void> delete(@PathVariable Long cityId) {
        cityService.delete(cityId);
        return ResponseEntity.noContent().build();
    }
}