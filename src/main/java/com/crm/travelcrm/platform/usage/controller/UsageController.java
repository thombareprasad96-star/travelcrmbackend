package com.crm.travelcrm.platform.usage.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.platform.usage.dto.TenantUsageResponse;
import com.crm.travelcrm.platform.usage.dto.UpdateTenantQuotaRequest;
import com.crm.travelcrm.platform.usage.dto.UsageDashboardResponse;
import com.crm.travelcrm.platform.usage.service.UsageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Tenant usage metering & quotas (SuperAdmin). Guarded by {@code ROLE_SUPER_ADMIN} via method
 * security — the platform filter chain has no URL matcher, so this annotation is load-bearing.
 * Keyed on {@code publicId}.
 */
@RestController
@RequestMapping("/api/super-admin/usage")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class UsageController {

    private final UsageService usageService;

    /** Every live tenant's usage vs quota + rollup header. */
    @GetMapping
    public ResponseEntity<ApiResponse<UsageDashboardResponse>> dashboard() {
        return ResponseEntity.ok(ApiResponse.success("Usage fetched", usageService.dashboard()));
    }

    /** A single tenant's usage vs quota. */
    @GetMapping("/{publicId}")
    public ResponseEntity<ApiResponse<TenantUsageResponse>> get(@PathVariable UUID publicId) {
        return ResponseEntity.ok(ApiResponse.success("Tenant usage fetched", usageService.getUsage(publicId)));
    }

    /** Override (or clear) a tenant's usage limits. */
    @PutMapping("/{publicId}/quota")
    public ResponseEntity<ApiResponse<TenantUsageResponse>> override(
            @PathVariable UUID publicId, @Valid @RequestBody UpdateTenantQuotaRequest request) {
        return ResponseEntity.ok(ApiResponse.success("Quota updated", usageService.overrideQuota(publicId, request)));
    }
}