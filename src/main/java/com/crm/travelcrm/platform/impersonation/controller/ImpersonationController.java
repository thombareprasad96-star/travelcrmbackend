package com.crm.travelcrm.platform.impersonation.controller;

import com.crm.travelcrm.auth.mfa.SuperAdminStepUpService;
import com.crm.travelcrm.common.entity.SuperAdmin;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.util.ClientIp;
import com.crm.travelcrm.platform.impersonation.dto.ImpersonationResponse;
import com.crm.travelcrm.platform.impersonation.service.ImpersonationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Impersonation endpoints. <b>Start</b> is SuperAdmin-only (platform realm). <b>End</b> is
 * intentionally NOT role-guarded: it is called from the tenant app carrying the impersonation
 * token (which authenticates as the target user), and it only records an audit row from the
 * token's own claims — so requiring {@code SUPER_ADMIN} would make it unreachable.
 */
@RestController
@RequiredArgsConstructor
public class ImpersonationController {

    private final ImpersonationService impersonationService;
    private final SuperAdminStepUpService superAdminStepUpService;

    @PostMapping("/api/super-admin/users/{publicId}/impersonate")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ResponseEntity<ApiResponse<ImpersonationResponse>> start(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal SuperAdmin superAdmin,
            @RequestHeader(value = SuperAdminStepUpService.MFA_CODE_HEADER, required = false) String mfaCode,
            HttpServletRequest request) {
        superAdminStepUpService.requireCode(
                superAdmin, mfaCode, "impersonation start",
                ClientIp.resolve(request), request.getHeader("User-Agent"));
        return ResponseEntity.ok(ApiResponse.success(
                "Impersonation session created",
                impersonationService.start(publicId,
                        ClientIp.resolve(request), request.getHeader("User-Agent"))));
    }

    @PostMapping("/api/impersonation/end")
    public ResponseEntity<ApiResponse<Void>> end(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletRequest request) {
        String token = (authHeader != null && authHeader.startsWith("Bearer "))
                ? authHeader.substring(7) : null;
        impersonationService.end(token, ClientIp.resolve(request), request.getHeader("User-Agent"));
        return ResponseEntity.ok(ApiResponse.success("Impersonation ended"));
    }
}
