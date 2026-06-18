package com.crm.travelcrm.master.geography.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.master.geography.dto.request.CreateDestinationRequest;
import com.crm.travelcrm.master.geography.dto.request.UpdateDestinationRequest;
import com.crm.travelcrm.master.geography.dto.response.CityDto;
import com.crm.travelcrm.master.geography.dto.response.DestinationDto;
import com.crm.travelcrm.master.geography.dto.response.DestinationListResponseDTO;
import com.crm.travelcrm.master.geography.mapper.CityMapper;
import com.crm.travelcrm.master.geography.repository.CityRepository;
import com.crm.travelcrm.master.geography.service.DestinationService;
import com.crm.travelcrm.master.geography.support.GeographySupport;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DestinationController {

    private final DestinationService destinationService;
    private final CityRepository cityRepository;
    private final CityMapper cityMapper;

    // ── GET paginated list ────────────────────────────────────────────────────

    @GetMapping("/destinations")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PagedApiResponse<DestinationListResponseDTO>> getAllDestinations(
            @RequestParam(defaultValue = "0")         int page,
            @RequestParam(defaultValue = "10")        int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc")      String sortDir) {

        Page<DestinationListResponseDTO> destinationPage =
                destinationService.getAllDestinations(page, size, sortBy, sortDir);

        return ResponseEntity.ok(
                PagedApiResponse.of(
                        "Destinations fetched successfully",
                        destinationPage.getContent(),
                        PaginationMeta.from(destinationPage, sortBy, sortDir)));
    }

    // ── GET by country (nested) ───────────────────────────────────────────────

    @GetMapping("/countries/{countryId}/destinations")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<PagedApiResponse<DestinationDto>> getByCountry(
            @PathVariable Long countryId,
            @RequestParam(defaultValue = "0")         int page,
            @RequestParam(defaultValue = "20")        int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc")      String sortDir) {

        return ResponseEntity.ok(
                destinationService.getByCountry(countryId, page, size, sortBy, sortDir));
    }

    // ── GET single ────────────────────────────────────────────────────────────

    @GetMapping("/destinations/{destinationId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<DestinationDto>> getById(
            @PathVariable Long destinationId) {

        return ResponseEntity.ok(
                ApiResponse.success("Destination fetched",
                        destinationService.getById(destinationId)));
    }

    // ── POST flat (frontend default) ──────────────────────────────────────────
    // countryId is in the request body. Used by DestinationMaster.jsx.

    @PostMapping("/destinations")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<ApiResponse<DestinationDto>> create(
            @Valid @RequestBody CreateDestinationRequest request) {

        DestinationDto created = destinationService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Destination created successfully", created, 201));
    }

    // ── POST nested (countryId in path) ───────────────────────────────────────

    @PostMapping("/countries/{countryId}/destinations")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<ApiResponse<DestinationDto>> createByCountry(
            @PathVariable Long countryId,
            @Valid @RequestBody CreateDestinationRequest request) {

        DestinationDto created = destinationService.create(countryId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Destination created successfully", created, 201));
    }

    // ── PUT ───────────────────────────────────────────────────────────────────

    @PutMapping("/destinations/{destinationId}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<ApiResponse<DestinationDto>> update(
            @PathVariable Long destinationId,
            @Valid @RequestBody UpdateDestinationRequest request) {

        return ResponseEntity.ok(
                ApiResponse.success("Destination updated successfully",
                        destinationService.update(destinationId, request)));
    }

    // ── GET cities by destination name — used by SightseeingService dropdown ──

    @GetMapping("/destinations/cities")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<CityDto>>> getCitiesByDestinationName(
            @RequestParam String destination) {
        Long tenantId = GeographySupport.currentTenantId();
        List<CityDto> cities = cityRepository
                .findByTenantIdAndDestination_NameIgnoreCase(tenantId, destination.trim())
                .stream().map(cityMapper::toDto).collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success("Cities fetched", cities));
    }

    // ── DELETE ────────────────────────────────────────────────────────────────

    @DeleteMapping("/destinations/{destinationId}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN', 'CRM_FULL')")
    public ResponseEntity<Void> delete(@PathVariable Long destinationId) {
        destinationService.delete(destinationId);
        return ResponseEntity.noContent().build();
    }
}