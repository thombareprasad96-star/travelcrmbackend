package com.crm.travelcrm.platform.subscription.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.platform.subscription.SubscriptionExpiryScheduler;
import com.crm.travelcrm.platform.subscription.dunning.DunningService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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

    /** Runs the trial/subscription expiry sweep immediately (same logic as the daily cron). */
    @PostMapping("/run-expiry")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> runExpiry() {
        int expired = expiryScheduler.expireOverdue();
        return ResponseEntity.ok(ApiResponse.success("Expiry sweep complete", Map.of("expired", expired)));
    }

    /** Runs the invoice-dunning sweep immediately (ACTIVE→PAST_DUE→EXPIRED; same logic as the daily cron). */
    @PostMapping("/run-dunning")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> runDunning() {
        int changed = dunningService.runDunning(LocalDate.now());
        return ResponseEntity.ok(ApiResponse.success("Dunning sweep complete", Map.of("changed", changed)));
    }
}