package com.crm.travelcrm.onboarding.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.onboarding.dto.OnboardingChecklistResponse;
import com.crm.travelcrm.onboarding.service.OnboardingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tenant-facing onboarding checklist. Shares the {@code /api/me} base with {@link
 * com.crm.travelcrm.platform.entitlement.controller.EntitlementController}; open to any authenticated
 * tenant user (SecurityConfig {@code anyRequest().authenticated()}), tenant taken from the
 * JWT-populated {@code TenantContext} — never a request param.
 */
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class OnboardingController {

    private final OnboardingService onboardingService;

    @GetMapping("/onboarding")
    public ResponseEntity<ApiResponse<OnboardingChecklistResponse>> onboarding() {
        return ResponseEntity.ok(ApiResponse.success(
                "Onboarding checklist fetched", onboardingService.getChecklist()));
    }

    @PostMapping("/onboarding/steps/{stepKey}/dismiss")
    public ResponseEntity<ApiResponse<Void>> dismiss(@PathVariable String stepKey) {
        onboardingService.dismissStep(stepKey);
        return ResponseEntity.ok(ApiResponse.success("Step dismissed"));
    }

    @DeleteMapping("/onboarding/steps/{stepKey}/dismiss")
    public ResponseEntity<ApiResponse<Void>> restore(@PathVariable String stepKey) {
        onboardingService.restoreStep(stepKey);
        return ResponseEntity.ok(ApiResponse.success("Step restored"));
    }
}