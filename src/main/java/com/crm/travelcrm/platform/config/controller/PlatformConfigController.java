package com.crm.travelcrm.platform.config.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.platform.config.dto.MaintenanceStatusResponse;
import com.crm.travelcrm.platform.config.dto.PlatformConfigResponse;
import com.crm.travelcrm.platform.config.dto.UpdateConfigRequest;
import com.crm.travelcrm.platform.config.dto.UpdateMaintenanceRequest;
import com.crm.travelcrm.platform.config.service.PlatformConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Global platform config + maintenance mode. SuperAdmin only. */
@RestController
@RequestMapping("/api/super-admin")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class PlatformConfigController {

    private final PlatformConfigService configService;

    @GetMapping("/config")
    public ResponseEntity<ApiResponse<List<PlatformConfigResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.success("Config fetched", configService.listConfig()));
    }

    // {key:.+} so dotted keys (e.g. maintenance.enabled) are captured whole, not truncated.
    @PutMapping("/config/{key:.+}")
    public ResponseEntity<ApiResponse<PlatformConfigResponse>> set(
            @PathVariable String key, @RequestBody UpdateConfigRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Config updated",
                configService.setConfig(key, request.getValue(), request.getDescription())));
    }

    @GetMapping("/maintenance")
    public ResponseEntity<ApiResponse<MaintenanceStatusResponse>> maintenance() {
        return ResponseEntity.ok(ApiResponse.success("Maintenance status", configService.maintenanceStatus()));
    }

    @PutMapping("/maintenance")
    public ResponseEntity<ApiResponse<MaintenanceStatusResponse>> setMaintenance(
            @RequestBody UpdateMaintenanceRequest request) {
        boolean enabled = Boolean.TRUE.equals(request.getEnabled());
        return ResponseEntity.ok(ApiResponse.success("Maintenance updated",
                configService.setMaintenance(enabled, request.getMessage())));
    }
}