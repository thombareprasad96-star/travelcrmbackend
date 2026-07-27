package com.crm.travelcrm.platform.subscription.controller;

import com.crm.travelcrm.auth.mfa.SuperAdminStepUpService;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.entity.SuperAdmin;
import com.crm.travelcrm.common.util.ClientIp;
import com.crm.travelcrm.platform.subscription.SubscriptionExpiryScheduler;
import com.crm.travelcrm.platform.subscription.dunning.DunningService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/** Subscription operations for the SuperAdmin (run the expiry / dunning sweeps on demand). */
@RestController
@RequestMapping("/api/super-admin/subscriptions")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionExpiryScheduler expiryScheduler;
    private final DunningService dunningService;
    private final SuperAdminStepUpService superAdminStepUpService;

    /** Runs the trial/subscription expiry sweep immediately (same logic as the daily cron). */
    @PostMapping("/run-expiry")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> runExpiry(
            @AuthenticationPrincipal SuperAdmin superAdmin,
            @RequestHeader(value = SuperAdminStepUpService.MFA_CODE_HEADER, required = false) String mfaCode,
            HttpServletRequest request) {
        requireStepUp(superAdmin, mfaCode, "run subscription expiry sweep", request);
        int expired = expiryScheduler.expireOverdue();
        return ResponseEntity.ok(ApiResponse.success("Expiry sweep complete", Map.of("expired", expired)));
    }

    /** Runs the invoice-dunning sweep immediately (ACTIVE→PAST_DUE→EXPIRED; same logic as the daily cron). */
    @PostMapping("/run-dunning")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> runDunning(
            @AuthenticationPrincipal SuperAdmin superAdmin,
            @RequestHeader(value = SuperAdminStepUpService.MFA_CODE_HEADER, required = false) String mfaCode,
            HttpServletRequest request) {
        requireStepUp(superAdmin, mfaCode, "run subscription dunning sweep", request);
        int changed = dunningService.runDunning(LocalDate.now());
        return ResponseEntity.ok(ApiResponse.success("Dunning sweep complete", Map.of("changed", changed)));
    }

    private void requireStepUp(SuperAdmin superAdmin, String mfaCode, String action,
                               HttpServletRequest request) {
        superAdminStepUpService.requireCode(
                superAdmin, mfaCode, action,
                ClientIp.resolve(request), request.getHeader("User-Agent"));
    }
}
