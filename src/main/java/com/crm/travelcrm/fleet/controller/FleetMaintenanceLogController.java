package com.crm.travelcrm.fleet.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.fleet.dto.FleetMaintenanceLogRequestDto;
import com.crm.travelcrm.fleet.dto.FleetMaintenanceLogResponseDto;
import com.crm.travelcrm.fleet.service.FleetLogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Maintenance diary — created/listed under a vehicle; edited/deleted by the log's own publicId. */
@RestController
@RequestMapping("/api/fleet")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('FLEET_READ')")   // class default; mutating methods override below
public class FleetMaintenanceLogController {

    private final FleetLogService logService;

    @PostMapping("/vehicles/{vehiclePublicId}/maintenance-logs")
    @PreAuthorize("hasAuthority('FLEET_CREATE')")
    public ResponseEntity<ApiResponse<FleetMaintenanceLogResponseDto>> add(
            @PathVariable UUID vehiclePublicId, @Valid @RequestBody FleetMaintenanceLogRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Maintenance log added successfully",
                        logService.addMaintenanceLog(vehiclePublicId, request), 201));
    }

    @GetMapping("/vehicles/{vehiclePublicId}/maintenance-logs")
    public ResponseEntity<PagedApiResponse<FleetMaintenanceLogResponseDto>> list(
            @PathVariable UUID vehiclePublicId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(logService.listMaintenanceLogs(vehiclePublicId, page, size));
    }

    @PutMapping("/maintenance-logs/{publicId}")
    @PreAuthorize("hasAuthority('FLEET_UPDATE')")
    public ResponseEntity<ApiResponse<FleetMaintenanceLogResponseDto>> update(
            @PathVariable UUID publicId, @Valid @RequestBody FleetMaintenanceLogRequestDto request) {
        return ResponseEntity.ok(ApiResponse.success("Maintenance log updated successfully",
                logService.updateMaintenanceLog(publicId, request)));
    }

    @DeleteMapping("/maintenance-logs/{publicId}")
    @PreAuthorize("hasAuthority('FLEET_DELETE')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID publicId) {
        logService.deleteMaintenanceLog(publicId);
        return ResponseEntity.ok(ApiResponse.success("Maintenance log moved to Trash"));
    }
}