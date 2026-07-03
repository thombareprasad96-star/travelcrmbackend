package com.crm.travelcrm.fleet.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.fleet.dto.FleetFuelLogRequestDto;
import com.crm.travelcrm.fleet.dto.FleetFuelLogResponseDto;
import com.crm.travelcrm.fleet.service.FleetLogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Fuel diary — created/listed under a vehicle; edited/deleted by the log's own publicId. */
@RestController
@RequestMapping("/api/fleet")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('FLEET_READ')")   // class default; mutating methods override below
public class FleetFuelLogController {

    private final FleetLogService logService;

    @PostMapping("/vehicles/{vehiclePublicId}/fuel-logs")
    @PreAuthorize("hasAuthority('FLEET_CREATE')")
    public ResponseEntity<ApiResponse<FleetFuelLogResponseDto>> add(
            @PathVariable UUID vehiclePublicId, @Valid @RequestBody FleetFuelLogRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Fuel log added successfully",
                        logService.addFuelLog(vehiclePublicId, request), 201));
    }

    @GetMapping("/vehicles/{vehiclePublicId}/fuel-logs")
    public ResponseEntity<PagedApiResponse<FleetFuelLogResponseDto>> list(
            @PathVariable UUID vehiclePublicId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(logService.listFuelLogs(vehiclePublicId, page, size));
    }

    @PutMapping("/fuel-logs/{publicId}")
    @PreAuthorize("hasAuthority('FLEET_UPDATE')")
    public ResponseEntity<ApiResponse<FleetFuelLogResponseDto>> update(
            @PathVariable UUID publicId, @Valid @RequestBody FleetFuelLogRequestDto request) {
        return ResponseEntity.ok(ApiResponse.success("Fuel log updated successfully",
                logService.updateFuelLog(publicId, request)));
    }

    @DeleteMapping("/fuel-logs/{publicId}")
    @PreAuthorize("hasAuthority('FLEET_DELETE')")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID publicId) {
        logService.deleteFuelLog(publicId);
        return ResponseEntity.ok(ApiResponse.success("Fuel log moved to Trash"));
    }
}