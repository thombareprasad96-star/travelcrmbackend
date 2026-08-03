package com.crm.travelcrm.fleet.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.fleet.dto.*;
import com.crm.travelcrm.fleet.service.FleetTripService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
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

    /**
     * Hand a running trip over to another vehicle and/or driver — breakdown, relief driver,
     * reallocation. Rides FLEET_UPDATE like the rest of the lifecycle: it is an operational act, not
     * a financial one, and it happens at 3am at Devprayag by whoever is awake.
     */
    @PatchMapping("/{publicId}/swap")
    @PreAuthorize("hasAuthority('FLEET_UPDATE')")
    public ResponseEntity<ApiResponse<FleetTripResponseDto>> swap(
            @PathVariable UUID publicId, @Valid @RequestBody FleetTripSwapDto request) {
        return ResponseEntity.ok(ApiResponse.success("Trip handed over",
                tripService.swap(publicId, request)));
    }

    @PatchMapping("/{publicId}/cancel")
    @PreAuthorize("hasAuthority('FLEET_UPDATE')")
    public ResponseEntity<ApiResponse<FleetTripResponseDto>> cancel(@PathVariable UUID publicId) {
        return ResponseEntity.ok(ApiResponse.success("Trip cancelled",
                tripService.cancel(publicId)));
    }

    /**
     * The trip's legs, in order. A single-leg trip reads like the trip itself; more than one means the
     * duty changed hands, and these rows are the only place the earlier vehicle, its odometer span and
     * its driver survive — the trip's own fields always point at the CURRENT leg.
     */
    @GetMapping("/{publicId}/legs")
    public ResponseEntity<ApiResponse<List<FleetTripLegDto>>> legs(@PathVariable UUID publicId) {
        return ResponseEntity.ok(ApiResponse.success("Legs fetched", tripService.legs(publicId)));
    }

    /**
     * The printable duty slip. Rides plain FLEET_READ: this is the paper an office prints for a
     * driver leaving at 5am, and gating it on the money grant would put it out of reach of exactly
     * the person whose job it is.
     */
    @GetMapping("/{publicId}/duty-slip")
    public ResponseEntity<byte[]> dutySlip(@PathVariable UUID publicId) {
        byte[] pdf = tripService.dutySlip(publicId);
        return ResponseEntity.ok()
                .header("Content-Disposition", "inline; filename=duty-slip-" + publicId + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    /** Handover reason catalogue for the swap form. */
    @GetMapping("/leg-change-reasons")
    public ResponseEntity<ApiResponse<List<FleetLegChangeReasonDto>>> legChangeReasons() {
        return ResponseEntity.ok(ApiResponse.success("Reasons fetched", tripService.legChangeReasons()));
    }

    @DeleteMapping("/{publicId}")
    @PreAuthorize("hasAuthority('FLEET_DELETE')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID publicId) {
        tripService.delete(publicId);
        return ResponseEntity.ok(ApiResponse.success("Trip moved to Trash"));
    }
}