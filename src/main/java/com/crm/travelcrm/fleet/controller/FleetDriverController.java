package com.crm.travelcrm.fleet.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.fleet.dto.*;
import com.crm.travelcrm.fleet.service.FleetDriverService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/fleet/drivers")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('FLEET_READ')")   // class default; mutating methods override below
public class FleetDriverController {

    private final FleetDriverService driverService;

    @PostMapping
    @PreAuthorize("hasAuthority('FLEET_CREATE')")
    public ResponseEntity<ApiResponse<FleetDriverResponseDto>> create(
            @Valid @RequestBody FleetDriverRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Driver created successfully",
                        driverService.create(request), 201));
    }

    @GetMapping
    public ResponseEntity<PagedApiResponse<FleetDriverResponseDto>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(driverService.list(status, search, page, size));
    }

    /** Slim dropdown options for the trip form, e.g. {@code ?status=ACTIVE}. */
    @GetMapping("/options")
    public ResponseEntity<ApiResponse<List<FleetOptionDto>>> options(
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(ApiResponse.success("Driver options fetched successfully",
                driverService.options(status)));
    }

    @GetMapping("/{publicId}")
    public ResponseEntity<ApiResponse<FleetDriverResponseDto>> getById(@PathVariable UUID publicId) {
        return ResponseEntity.ok(ApiResponse.success("Driver fetched successfully",
                driverService.getByPublicId(publicId)));
    }

    @PutMapping("/{publicId}")
    @PreAuthorize("hasAuthority('FLEET_UPDATE')")
    public ResponseEntity<ApiResponse<FleetDriverResponseDto>> update(
            @PathVariable UUID publicId, @Valid @RequestBody FleetDriverRequestDto request) {
        return ResponseEntity.ok(ApiResponse.success("Driver updated successfully",
                driverService.update(publicId, request)));
    }

    @PatchMapping("/{publicId}/status")
    @PreAuthorize("hasAuthority('FLEET_UPDATE')")
    public ResponseEntity<ApiResponse<FleetDriverResponseDto>> changeStatus(
            @PathVariable UUID publicId, @Valid @RequestBody FleetDriverStatusUpdateDto request) {
        return ResponseEntity.ok(ApiResponse.success("Driver status updated successfully",
                driverService.changeStatus(publicId, request.getStatus().name())));
    }

    @DeleteMapping("/{publicId}")
    @PreAuthorize("hasAuthority('FLEET_DELETE')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID publicId) {
        driverService.delete(publicId);
        return ResponseEntity.ok(ApiResponse.success("Driver moved to Trash"));
    }
}