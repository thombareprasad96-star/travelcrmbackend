package com.crm.travelcrm.platform.console;

import com.crm.travelcrm.auth.dto.SuperAdminMfaStatusResponse;
import com.crm.travelcrm.auth.mfa.SuperAdminMfaService;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.entity.SuperAdmin;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only MFA status. Setup is mandatory and happens during SuperAdmin login. */
@RestController
@RequestMapping("/api/super-admin/me/mfa")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class SuperAdminMfaController {

    private final SuperAdminMfaService mfaService;

    @GetMapping
    public ResponseEntity<ApiResponse<SuperAdminMfaStatusResponse>> status(
            @AuthenticationPrincipal SuperAdmin superAdmin) {
        return ResponseEntity.ok(ApiResponse.success("MFA status fetched", mfaService.status(superAdmin)));
    }
}
