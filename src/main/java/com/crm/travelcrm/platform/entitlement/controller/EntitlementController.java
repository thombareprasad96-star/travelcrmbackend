package com.crm.travelcrm.platform.entitlement.controller;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.platform.entitlement.dto.MyEntitlementsResponse;
import com.crm.travelcrm.platform.entitlement.service.TenantEntitlementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-facing entitlements — the authenticated user's effective modules + plan limits, used by
 * the tenant app to soft-gate (hide) modules its plan doesn't include. Any authenticated tenant
 * user; the tenant is taken from the JWT-populated {@link TenantContext}, never a request param.
 */
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class EntitlementController {

    private final TenantEntitlementService entitlementService;

    @GetMapping("/entitlements")
    public ResponseEntity<ApiResponse<MyEntitlementsResponse>> myEntitlements() {
        return ResponseEntity.ok(ApiResponse.success("Entitlements fetched",
                entitlementService.entitlementsForTenant(TenantContext.getTenantId())));
    }
}