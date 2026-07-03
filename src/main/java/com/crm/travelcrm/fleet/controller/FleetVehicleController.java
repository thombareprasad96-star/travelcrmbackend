package com.crm.travelcrm.fleet.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.fleet.dto.*;
import com.crm.travelcrm.fleet.service.FleetVehicleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/fleet/vehicles")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('FLEET_READ')")   // class default; mutating methods override below
public class FleetVehicleController {

    private final FleetVehicleService vehicleService;

    @PostMapping
    @PreAuthorize("hasAuthority('FLEET_CREATE')")
    public ResponseEntity<ApiResponse<FleetVehicleResponseDto>> create(
            @Valid @RequestBody FleetVehicleRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Vehicle created successfully",
                        vehicleService.create(request), 201));
    }

    @GetMapping
    public ResponseEntity<PagedApiResponse<FleetVehicleResponseDto>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String ownerType,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(vehicleService.list(status, ownerType, search, page, size));
    }

    /** Slim dropdown options for trip/log forms, e.g. {@code ?status=AVAILABLE}. */
    @GetMapping("/options")
    public ResponseEntity<ApiResponse<List<FleetOptionDto>>> options(
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(ApiResponse.success("Vehicle options fetched successfully",
                vehicleService.options(status)));
    }

    /** Active vendors for the vehicle form's owner link (VENDOR / RENTED). */
    @GetMapping("/vendor-options")
    public ResponseEntity<ApiResponse<List<FleetOptionDto>>> vendorOptions() {
        return ResponseEntity.ok(ApiResponse.success("Vendor options fetched successfully",
                vehicleService.vendorOptions()));
    }

    @GetMapping("/{publicId}")
    public ResponseEntity<ApiResponse<FleetVehicleResponseDto>> getById(@PathVariable UUID publicId) {
        return ResponseEntity.ok(ApiResponse.success("Vehicle fetched successfully",
                vehicleService.getByPublicId(publicId)));
    }

    @PutMapping("/{publicId}")
    @PreAuthorize("hasAuthority('FLEET_UPDATE')")
    public ResponseEntity<ApiResponse<FleetVehicleResponseDto>> update(
            @PathVariable UUID publicId, @Valid @RequestBody FleetVehicleRequestDto request) {
        return ResponseEntity.ok(ApiResponse.success("Vehicle updated successfully",
                vehicleService.update(publicId, request)));
    }

    @PatchMapping("/{publicId}/status")
    @PreAuthorize("hasAuthority('FLEET_UPDATE')")
    public ResponseEntity<ApiResponse<FleetVehicleResponseDto>> changeStatus(
            @PathVariable UUID publicId, @Valid @RequestBody FleetVehicleStatusUpdateDto request) {
        return ResponseEntity.ok(ApiResponse.success("Vehicle status updated successfully",
                vehicleService.changeStatus(publicId, request.getStatus().name())));
    }

    @DeleteMapping("/{publicId}")
    @PreAuthorize("hasAuthority('FLEET_DELETE')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID publicId) {
        vehicleService.delete(publicId);
        return ResponseEntity.ok(ApiResponse.success("Vehicle moved to Trash"));
    }
}