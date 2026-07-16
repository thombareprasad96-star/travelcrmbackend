package com.crm.travelcrm.subagent.license.controller;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.subagent.license.dto.OpenSubAgentLicenseRequest;
import com.crm.travelcrm.subagent.license.dto.SubAgentLicenseRequestResponse;
import com.crm.travelcrm.subagent.license.service.SubAgentLicenseService;
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
 * Tenant-facing sub-agent (Travel Partner) seat-license purchases. The first request is created
 * automatically when a sub-agent is provisioned over the seat cap ({@code SubAgentController.create});
 * this controller lets the tenant admin re-open payment after a rejection, upload an offline proof,
 * cancel a pending purchase, and view purchase history. Gated on {@code USER_*} — only the TENANT_ADMIN
 * holds those, matching {@code SubAgentController}. A SuperAdmin resolves to a null principal → 403.
 */
@RestController
@RequestMapping("/api/subagents/license-requests")
@RequiredArgsConstructor
public class SubAgentLicenseController {

    private final SubAgentLicenseService licenseService;

    private static User require(User currentUser) {
        if (currentUser == null) {
            throw new BusinessException("This endpoint is available to tenant users only.", HttpStatus.FORBIDDEN);
        }
        return currentUser;
    }

    /** Open (or resubmit) a seat-license purchase for an existing PENDING_LICENSE sub-agent. */
    @PostMapping
    @PreAuthorize("hasAuthority('USER_CREATE')")
    public ResponseEntity<ApiResponse<SubAgentLicenseRequestResponse>> open(
            @Valid @RequestBody OpenSubAgentLicenseRequest request,
            @AuthenticationPrincipal User currentUser) {
        User u = require(currentUser);
        SubAgentLicenseRequestResponse res = licenseService.openForPending(
                u.getTenantId(), u.getUsername(), request.getSubAgentPublicId(), request.getPaymentMode(),
                request.getOfflineMode(), request.getOfflineReference(), request.getOfflineNotes());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Seat-license request submitted", res, HttpStatus.CREATED.value()));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('USER_READ')")
    public ResponseEntity<ApiResponse<List<SubAgentLicenseRequestResponse>>> list(
            @AuthenticationPrincipal User currentUser) {
        User u = require(currentUser);
        return ResponseEntity.ok(ApiResponse.success(
                "Seat-license requests fetched", licenseService.listForTenant(u.getTenantId())));
    }

    @PostMapping("/{publicId}/proof")
    @PreAuthorize("hasAuthority('USER_CREATE')")
    public ResponseEntity<ApiResponse<SubAgentLicenseRequestResponse>> uploadProof(
            @PathVariable UUID publicId,
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal User currentUser) {
        User u = require(currentUser);
        return ResponseEntity.ok(ApiResponse.success(
                "Proof uploaded", licenseService.uploadProof(u.getTenantId(), publicId, file)));
    }

    @PostMapping("/{publicId}/cancel")
    @PreAuthorize("hasAuthority('USER_UPDATE')")
    public ResponseEntity<ApiResponse<SubAgentLicenseRequestResponse>> cancel(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal User currentUser) {
        User u = require(currentUser);
        return ResponseEntity.ok(ApiResponse.success(
                "Seat-license request cancelled", licenseService.cancel(u.getTenantId(), publicId)));
    }
}