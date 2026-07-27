package com.crm.travelcrm.platform.subscription.upgrade.controller;

import com.crm.travelcrm.auth.mfa.SuperAdminStepUpService;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.entity.SuperAdmin;
import com.crm.travelcrm.common.util.ClientIp;
import com.crm.travelcrm.platform.subscription.upgrade.dto.RejectUpgradeRequestRequest;
import com.crm.travelcrm.platform.subscription.upgrade.dto.UpgradeRequestResponse;
import com.crm.travelcrm.platform.subscription.upgrade.enums.UpgradeRequestStatus;
import com.crm.travelcrm.platform.subscription.upgrade.service.UpgradeRequestService;
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
 * SuperAdmin review of tenant plan-upgrade requests. Approving activates the plan; rejecting keeps the
 * tenant on its current plan. Cross-tenant, platform realm — secured by {@code ROLE_SUPER_ADMIN}.
 */
@RestController
@RequestMapping("/api/super-admin/upgrade-requests")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class UpgradeRequestAdminController {

    private final UpgradeRequestService upgradeRequestService;
    private final SuperAdminStepUpService superAdminStepUpService;

    /** List requests, optionally filtered by status. Unknown/blank status → all (lenient, never 400). */
    @GetMapping
    public ResponseEntity<ApiResponse<List<UpgradeRequestResponse>>> list(
            @RequestParam(value = "status", required = false) String status) {
        UpgradeRequestStatus filter = parseStatus(status);
        return ResponseEntity.ok(ApiResponse.success(
                "Upgrade requests fetched", upgradeRequestService.listForAdmin(filter)));
    }

    /** Pending-count badge for the console sidebar. */
    @GetMapping("/pending-count")
    public ResponseEntity<ApiResponse<Map<String, Long>>> pendingCount() {
        return ResponseEntity.ok(ApiResponse.success(
                "Pending count", Map.of("count", upgradeRequestService.pendingCount())));
    }

    @PostMapping("/{publicId}/approve")
    public ResponseEntity<ApiResponse<UpgradeRequestResponse>> approve(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal SuperAdmin superAdmin,
            @RequestHeader(value = SuperAdminStepUpService.MFA_CODE_HEADER, required = false) String mfaCode,
            HttpServletRequest request) {
        requireStepUp(superAdmin, mfaCode, "approve tenant plan-upgrade request", request);
        return ResponseEntity.ok(ApiResponse.success(
                "Upgrade request approved", upgradeRequestService.approve(publicId)));
    }

    @PostMapping("/{publicId}/reject")
    public ResponseEntity<ApiResponse<UpgradeRequestResponse>> reject(
            @PathVariable UUID publicId,
            @Valid @RequestBody RejectUpgradeRequestRequest request,
            @AuthenticationPrincipal SuperAdmin superAdmin,
            @RequestHeader(value = SuperAdminStepUpService.MFA_CODE_HEADER, required = false) String mfaCode,
            HttpServletRequest httpRequest) {
        requireStepUp(superAdmin, mfaCode, "reject tenant plan-upgrade request", httpRequest);
        return ResponseEntity.ok(ApiResponse.success(
                "Upgrade request rejected", upgradeRequestService.reject(publicId, request.getReason())));
    }

    private void requireStepUp(SuperAdmin superAdmin, String mfaCode, String action,
                               HttpServletRequest request) {
        superAdminStepUpService.requireCode(
                superAdmin, mfaCode, action,
                ClientIp.resolve(request), request.getHeader("User-Agent"));
    }

    private static UpgradeRequestStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UpgradeRequestStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;   // unknown filter → treat as "all"
        }
    }
}
