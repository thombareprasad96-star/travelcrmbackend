// common/dto/ApiResponse.java
package com.crm.travelcrm.tenent.controller;

import com.crm.travelcrm.common.dto.ApiResponse;


import com.crm.travelcrm.tenent.dto.CreateTenantRequest;
import com.crm.travelcrm.tenent.dto.TenantResponse;
import com.crm.travelcrm.tenent.dto.UpdateTenantRequest;
import com.crm.travelcrm.tenent.service.TenantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/super-admin/tenants")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class TenantController {

    private final TenantService tenantService;

    @PostMapping
    public ResponseEntity<ApiResponse<TenantResponse>> createTenant(
            @Valid @RequestBody CreateTenantRequest request) {

        log.info("POST /api/super-admin/tenants - {}", request.getOrganizationCode());
        TenantResponse response = tenantService.createTenant(request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Tenant created successfully", response));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<TenantResponse>>> getAllTenants() {

        log.info("GET /api/super-admin/tenants");
        return ResponseEntity.ok(
                ApiResponse.success("Tenants fetched successfully",
                        tenantService.getAllTenants()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TenantResponse>> getTenantById(
            @PathVariable Long id) {

        log.info("GET /api/super-admin/tenants/{}", id);
        return ResponseEntity.ok(
                ApiResponse.success("Tenant fetched successfully",
                        tenantService.getTenantById(id)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<TenantResponse>> updateTenant(
            @PathVariable Long id,
            @Valid @RequestBody UpdateTenantRequest request) {

        log.info("PUT /api/super-admin/tenants/{}", id);
        return ResponseEntity.ok(
                ApiResponse.success("Tenant updated successfully",
                        tenantService.updateTenant(id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteTenant(
            @PathVariable Long id) {

        log.info("DELETE /api/super-admin/tenants/{}", id);
        tenantService.deleteTenant(id);
        return ResponseEntity.ok(
                ApiResponse.success("Tenant deleted successfully", null));
    }
}