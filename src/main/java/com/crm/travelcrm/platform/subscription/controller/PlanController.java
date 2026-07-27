package com.crm.travelcrm.platform.subscription.controller;

import com.crm.travelcrm.auth.mfa.SuperAdminStepUpService;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.entity.SuperAdmin;
import com.crm.travelcrm.common.util.ClientIp;
import com.crm.travelcrm.platform.subscription.dto.PlanResponse;
import com.crm.travelcrm.platform.subscription.dto.UpdatePlanRequest;
import com.crm.travelcrm.platform.subscription.service.PlanService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Platform plan catalogue. Guarded by {@code ROLE_SUPER_ADMIN}; keyed on {@code publicId}. */
@RestController
@RequestMapping("/api/super-admin/plans")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class PlanController {

    private final PlanService planService;
    private final SuperAdminStepUpService superAdminStepUpService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<PlanResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.success("Plans fetched", planService.listPlans()));
    }

    @GetMapping("/{publicId}")
    public ResponseEntity<ApiResponse<PlanResponse>> get(@PathVariable UUID publicId) {
        return ResponseEntity.ok(ApiResponse.success("Plan fetched", planService.getPlan(publicId)));
    }

    @PutMapping("/{publicId}")
    public ResponseEntity<ApiResponse<PlanResponse>> update(
            @PathVariable UUID publicId,
            @Valid @RequestBody UpdatePlanRequest request,
            @AuthenticationPrincipal SuperAdmin superAdmin,
            @RequestHeader(value = SuperAdminStepUpService.MFA_CODE_HEADER, required = false) String mfaCode,
            HttpServletRequest httpRequest) {
        superAdminStepUpService.requireCode(
                superAdmin, mfaCode, "plan/pricing change",
                ClientIp.resolve(httpRequest), httpRequest.getHeader("User-Agent"));
        return ResponseEntity.ok(ApiResponse.success("Plan updated", planService.updatePlan(publicId, request)));
    }
}
