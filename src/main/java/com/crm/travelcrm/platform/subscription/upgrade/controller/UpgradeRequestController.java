package com.crm.travelcrm.platform.subscription.upgrade.controller;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.platform.subscription.upgrade.dto.CreateUpgradeRequestRequest;
import com.crm.travelcrm.platform.subscription.upgrade.dto.UpgradeRequestResponse;
import com.crm.travelcrm.platform.subscription.upgrade.service.UpgradeRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Tenant self-serve plan-upgrade requests (the caller's own tenant, from the principal). Submitting a
 * request does NOT change the plan — it stays PENDING until a SuperAdmin approves. Viewing is open to
 * any authenticated tenant user; submitting/cancelling requires {@code SETTINGS_MANAGE} (tenant admin),
 * matching {@code MySubscriptionController}. A SuperAdmin on the same chain resolves to a null
 * principal → 403 (never a 500 NPE).
 */
@RestController
@RequestMapping("/api/company/subscription/upgrade-requests")
@RequiredArgsConstructor
public class UpgradeRequestController {

    private final UpgradeRequestService upgradeRequestService;

    private static User require(User currentUser) {
        if (currentUser == null) {
            throw new BusinessException("This endpoint is available to tenant users only.", HttpStatus.FORBIDDEN);
        }
        return currentUser;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SETTINGS_MANAGE')")
    public ResponseEntity<ApiResponse<UpgradeRequestResponse>> create(
            @Valid @RequestBody CreateUpgradeRequestRequest request,
            @AuthenticationPrincipal User currentUser) {
        User u = require(currentUser);
        UpgradeRequestResponse res = upgradeRequestService.create(u.getTenantId(), u.getUsername(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Upgrade request submitted", res, HttpStatus.CREATED.value()));
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<UpgradeRequestResponse>>> list(
            @AuthenticationPrincipal User currentUser) {
        User u = require(currentUser);
        return ResponseEntity.ok(ApiResponse.success(
                "Upgrade requests fetched", upgradeRequestService.listForTenant(u.getTenantId())));
    }

    @PostMapping("/{publicId}/proof")
    @PreAuthorize("hasAuthority('SETTINGS_MANAGE')")
    public ResponseEntity<ApiResponse<UpgradeRequestResponse>> uploadProof(
            @PathVariable UUID publicId,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal User currentUser) {
        User u = require(currentUser);
        return ResponseEntity.ok(ApiResponse.success(
                "Proof uploaded", upgradeRequestService.uploadProof(u.getTenantId(), publicId, file)));
    }

    @PostMapping("/{publicId}/cancel")
    @PreAuthorize("hasAuthority('SETTINGS_MANAGE')")
    public ResponseEntity<ApiResponse<UpgradeRequestResponse>> cancel(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal User currentUser) {
        User u = require(currentUser);
        return ResponseEntity.ok(ApiResponse.success(
                "Upgrade request cancelled", upgradeRequestService.cancel(u.getTenantId(), publicId)));
    }
}
