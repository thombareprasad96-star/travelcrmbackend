package com.crm.travelcrm.fleet.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.fleet.dto.*;
import com.crm.travelcrm.fleet.service.FleetTripService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/fleet/trips")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('FLEET_READ')")   // class default; mutating methods override below
public class FleetTripController {

    private final FleetTripService tripService;

    /** Create PLANNED (or a post-facto COMPLETED entry when {@code endDatetime} is sent). */
    @PostMapping
    @PreAuthorize("hasAuthority('FLEET_CREATE')")
    public ResponseEntity<ApiResponse<FleetTripResponseDto>> create(
            @Valid @RequestBody FleetTripCreateDto request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Trip created successfully",
                        tripService.create(request), 201));
    }

    @GetMapping
    public ResponseEntity<PagedApiResponse<FleetTripResponseDto>> list(
            @RequestParam(required = false) UUID vehicleId,
            @RequestParam(required = false) UUID driverId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID bookingId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(tripService.list(
                vehicleId, driverId, status, bookingId, fromDate, toDate, search, page, size));
    }

    @GetMapping("/{publicId}")
    public ResponseEntity<ApiResponse<FleetTripResponseDto>> getById(@PathVariable UUID publicId) {
        return ResponseEntity.ok(ApiResponse.success("Trip fetched successfully",
                tripService.getByPublicId(publicId)));
    }

    @PutMapping("/{publicId}")
    @PreAuthorize("hasAuthority('FLEET_UPDATE')")
    public ResponseEntity<ApiResponse<FleetTripResponseDto>> update(
            @PathVariable UUID publicId, @Valid @RequestBody FleetTripUpdateDto request) {
        return ResponseEntity.ok(ApiResponse.success("Trip updated successfully",
                tripService.update(publicId, request)));
    }

    @PatchMapping("/{publicId}/start")
    @PreAuthorize("hasAuthority('FLEET_UPDATE')")
    public ResponseEntity<ApiResponse<FleetTripResponseDto>> start(
            @PathVariable UUID publicId, @Valid @RequestBody FleetTripStartDto request) {
        return ResponseEntity.ok(ApiResponse.success("Trip started",
                tripService.start(publicId, request)));
    }

    @PatchMapping("/{publicId}/close")
    @PreAuthorize("hasAuthority('FLEET_UPDATE')")
    public ResponseEntity<ApiResponse<FleetTripResponseDto>> close(
            @PathVariable UUID publicId, @Valid @RequestBody FleetTripCloseDto request) {
        return ResponseEntity.ok(ApiResponse.success("Trip closed",
                tripService.close(publicId, request)));
    }

    @PatchMapping("/{publicId}/cancel")
    @PreAuthorize("hasAuthority('FLEET_UPDATE')")
    public ResponseEntity<ApiResponse<FleetTripResponseDto>> cancel(@PathVariable UUID publicId) {
        return ResponseEntity.ok(ApiResponse.success("Trip cancelled",
                tripService.cancel(publicId)));
    }

    @DeleteMapping("/{publicId}")
    @PreAuthorize("hasAuthority('FLEET_DELETE')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID publicId) {
        tripService.delete(publicId);
        return ResponseEntity.ok(ApiResponse.success("Trip moved to Trash"));
    }
}