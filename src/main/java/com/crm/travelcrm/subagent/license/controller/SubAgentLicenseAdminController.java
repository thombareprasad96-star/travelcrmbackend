package com.crm.travelcrm.subagent.license.controller;

import com.crm.travelcrm.auth.mfa.SuperAdminStepUpService;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.entity.SuperAdmin;
import com.crm.travelcrm.common.util.ClientIp;
import com.crm.travelcrm.subagent.license.dto.RejectSubAgentLicenseRequest;
import com.crm.travelcrm.subagent.license.dto.SubAgentLicenseRequestResponse;
import com.crm.travelcrm.subagent.license.enums.SubAgentLicenseRequestStatus;
import com.crm.travelcrm.subagent.license.service.SubAgentLicenseService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SuperAdmin review of tenant sub-agent seat-license requests — the seat-add-on queue that surfaces
 * alongside plan-upgrade requests in the one Subscription/Add-on Requests console. Approving grants the
 * seats + activates the pending sub-agent; rejecting leaves the sub-agent PENDING_LICENSE. Cross-tenant,
 * platform realm — secured by {@code ROLE_SUPER_ADMIN}.
 */
@RestController
@RequestMapping("/api/super-admin/subagent-license-requests")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class SubAgentLicenseAdminController {

    private final SubAgentLicenseService licenseService;
    private final SuperAdminStepUpService superAdminStepUpService;

    /** List requests, optionally filtered by status. Unknown/blank status → all (lenient, never 400). */
    @GetMapping
    public ResponseEntity<ApiResponse<List<SubAgentLicenseRequestResponse>>> list(
            @RequestParam(value = "status", required = false) String status) {
        SubAgentLicenseRequestStatus filter = parseStatus(status);
        return ResponseEntity.ok(ApiResponse.success(
                "Seat-license requests fetched", licenseService.listForAdmin(filter)));
    }

    /** Pending-count badge for the console sidebar. */
    @GetMapping("/pending-count")
    public ResponseEntity<ApiResponse<Map<String, Long>>> pendingCount() {
        return ResponseEntity.ok(ApiResponse.success(
                "Pending count", Map.of("count", licenseService.pendingCount())));
    }

    @PostMapping("/{publicId}/approve")
    public ResponseEntity<ApiResponse<SubAgentLicenseRequestResponse>> approve(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal SuperAdmin superAdmin,
            @RequestHeader(value = SuperAdminStepUpService.MFA_CODE_HEADER, required = false) String mfaCode,
            HttpServletRequest request) {
        requireStepUp(superAdmin, mfaCode, "approve sub-agent seat-license request", request);
        return ResponseEntity.ok(ApiResponse.success(
                "Seat-license request approved", licenseService.approve(publicId)));
    }

    @PostMapping("/{publicId}/reject")
    public ResponseEntity<ApiResponse<SubAgentLicenseRequestResponse>> reject(
            @PathVariable UUID publicId,
            @Valid @RequestBody RejectSubAgentLicenseRequest request,
            @AuthenticationPrincipal SuperAdmin superAdmin,
            @RequestHeader(value = SuperAdminStepUpService.MFA_CODE_HEADER, required = false) String mfaCode,
            HttpServletRequest httpRequest) {
        requireStepUp(superAdmin, mfaCode, "reject sub-agent seat-license request", httpRequest);
        return ResponseEntity.ok(ApiResponse.success(
                "Seat-license request rejected", licenseService.reject(publicId, request.getReason())));
    }

    private void requireStepUp(SuperAdmin superAdmin, String mfaCode, String action,
                               HttpServletRequest request) {
        superAdminStepUpService.requireCode(
                superAdmin, mfaCode, action,
                ClientIp.resolve(request), request.getHeader("User-Agent"));
    }

    private static SubAgentLicenseRequestStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return SubAgentLicenseRequestStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;   // unknown filter → treat as "all"
        }
    }
}
