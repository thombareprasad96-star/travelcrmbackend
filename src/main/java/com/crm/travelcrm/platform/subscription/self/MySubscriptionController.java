package com.crm.travelcrm.platform.subscription.self;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.platform.billing.dto.BillingRecordResponse;
import com.crm.travelcrm.platform.subscription.dto.PlanResponse;
import com.crm.travelcrm.platform.subscription.self.dto.MyPlanChangeResponse;
import com.crm.travelcrm.tenent.dto.ChangePlanRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Tenant self-serve subscription endpoints (the caller's own tenant, from the principal). Complements
 * {@code GET /api/company/subscription} (the summary on {@code CompanyController}) with the plan
 * catalogue, invoice history, and a proration-aware plan change. Viewing is open to any authenticated
 * tenant user; changing the plan requires {@code SETTINGS_MANAGE} (tenant admin), like company edits.
 */
@RestController
@RequestMapping("/api/company/subscription")
@RequiredArgsConstructor
public class MySubscriptionController {

    private final MySubscriptionService mySubscriptionService;

    /** Tenant-scoped: a SuperAdmin on the same chain resolves to a null principal — 403, not a 500 NPE. */
    private static Long tenantId(User currentUser) {
        if (currentUser == null) {
            throw new BusinessException("This endpoint is available to tenant users only.", HttpStatus.FORBIDDEN);
        }
        return currentUser.getTenantId();
    }

    @GetMapping("/plans")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<PlanResponse>>> plans() {
        return ResponseEntity.ok(ApiResponse.success(
                "Plans fetched", mySubscriptionService.listAvailablePlans()));
    }

    @GetMapping("/invoices")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<BillingRecordResponse>>> invoices(
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                "Invoices fetched", mySubscriptionService.listInvoices(tenantId(currentUser))));
    }

    @PostMapping("/change")
    @PreAuthorize("hasAuthority('SETTINGS_MANAGE')")
    public ResponseEntity<ApiResponse<MyPlanChangeResponse>> change(
            @Valid @RequestBody ChangePlanRequest request,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                "Plan changed", mySubscriptionService.changePlan(tenantId(currentUser), request.getPlan())));
    }
}