package com.crm.travelcrm.platform.entitlement.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.platform.entitlement.dto.TenantModulesResponse;
import com.crm.travelcrm.platform.entitlement.dto.UpdateModulesRequest;
import com.crm.travelcrm.platform.entitlement.service.TenantEntitlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Per-tenant module entitlements (Feature Flags). SuperAdmin only; keyed on {@code publicId}. */
@RestController
@RequestMapping("/api/super-admin/tenants")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class FeatureFlagController {

    private final TenantEntitlementService entitlementService;

    @GetMapping("/{publicId}/modules")
    public ResponseEntity<ApiResponse<TenantModulesResponse>> getModules(@PathVariable UUID publicId) {
        return ResponseEntity.ok(ApiResponse.success("Modules fetched",
                entitlementService.getModules(publicId)));
    }

    @PutMapping("/{publicId}/modules")
    public ResponseEntity<ApiResponse<TenantModulesResponse>> updateModules(
            @PathVariable UUID publicId, @RequestBody UpdateModulesRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Modules updated",
                entitlementService.updateModules(publicId, request.getModules())));
    }
}