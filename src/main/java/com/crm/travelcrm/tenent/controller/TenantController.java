package com.crm.travelcrm.tenent.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.tenent.dto.ChangePlanRequest;
import com.crm.travelcrm.tenent.dto.CreateTenantRequest;
import com.crm.travelcrm.tenent.dto.HardDeleteTenantRequest;
import com.crm.travelcrm.tenent.dto.TenantResponse;
import com.crm.travelcrm.tenent.dto.TenantSummaryResponse;
import com.crm.travelcrm.tenent.dto.UpdateTenantRequest;
import com.crm.travelcrm.tenent.enums.TenantStatus;
import com.crm.travelcrm.tenent.service.TenantService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Platform (SuperAdmin) tenant management. Keyed on {@code publicId} (UUID) — the internal Long PK
 * is never exposed. Guarded by {@code ROLE_SUPER_ADMIN}; a tenant token can never reach it.
 */
@Slf4j
@RestController
@RequestMapping("/api/super-admin/tenants")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class TenantController {

    private final TenantService tenantService;

    @PostMapping
    public ResponseEntity<ApiResponse<TenantResponse>> create(
            @Valid @RequestBody CreateTenantRequest request) {
        log.info("POST /api/super-admin/tenants - {}", request.getOrganizationCode());
        TenantResponse response = tenantService.createTenant(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Tenant created successfully", response));
    }

    @GetMapping
    public ResponseEntity<PagedApiResponse<TenantSummaryResponse>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) TenantStatus status,
            @RequestParam(defaultValue = "false") boolean deleted,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<TenantSummaryResponse> result =
                tenantService.listTenants(search, status, deleted, pageable);

        return ResponseEntity.ok(PagedApiResponse.of(
                "Tenants fetched successfully", result.getContent(), PaginationMeta.from(result)));
    }

    @GetMapping("/{publicId}")
    public ResponseEntity<ApiResponse<TenantResponse>> get(@PathVariable UUID publicId) {
        return ResponseEntity.ok(ApiResponse.success(
                "Tenant fetched successfully", tenantService.getTenant(publicId)));
    }

    @PutMapping("/{publicId}")
    public ResponseEntity<ApiResponse<TenantResponse>> update(
            @PathVariable UUID publicId, @Valid @RequestBody UpdateTenantRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Tenant updated successfully", tenantService.updateTenant(publicId, request)));
    }

    @PostMapping("/{publicId}/plan")
    public ResponseEntity<ApiResponse<TenantResponse>> changePlan(
            @PathVariable UUID publicId, @Valid @RequestBody ChangePlanRequest request) {
        return ResponseEntity.ok(ApiResponse.success(
                "Plan changed", tenantService.changePlan(publicId, request.getPlan())));
    }

    @PostMapping("/{publicId}/suspend")
    public ResponseEntity<ApiResponse<Void>> suspend(@PathVariable UUID publicId) {
        tenantService.suspend(publicId);
        return ResponseEntity.ok(ApiResponse.success("Tenant suspended", null));
    }

    @PostMapping("/{publicId}/reactivate")
    public ResponseEntity<ApiResponse<Void>> reactivate(@PathVariable UUID publicId) {
        tenantService.reactivate(publicId);
        return ResponseEntity.ok(ApiResponse.success("Tenant reactivated", null));
    }

    @DeleteMapping("/{publicId}")
    public ResponseEntity<ApiResponse<Void>> softDelete(@PathVariable UUID publicId) {
        tenantService.softDelete(publicId);
        return ResponseEntity.ok(ApiResponse.success("Tenant deleted", null));
    }

    @PostMapping("/{publicId}/restore")
    public ResponseEntity<ApiResponse<Void>> restore(@PathVariable UUID publicId) {
        tenantService.restore(publicId);
        return ResponseEntity.ok(ApiResponse.success("Tenant restored", null));
    }

    /** DANGER ZONE — irreversible. Requires the tenant to be soft-deleted and the org code echoed. */
    @PostMapping("/{publicId}/hard-delete")
    public ResponseEntity<ApiResponse<Void>> hardDelete(
            @PathVariable UUID publicId, @RequestBody HardDeleteTenantRequest request) {
        tenantService.hardDelete(publicId, request.getOrganizationCode());
        return ResponseEntity.ok(ApiResponse.success("Tenant permanently deleted", null));
    }
}