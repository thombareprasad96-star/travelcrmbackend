package com.crm.travelcrm.platform.console;

import com.crm.travelcrm.auth.mfa.SuperAdminStepUpService;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.entity.SuperAdmin;
import com.crm.travelcrm.common.util.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/super-admin/accounts")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class SuperAdminAccountController {

    private final SuperAdminAccountService accountService;
    private final SuperAdminStepUpService superAdminStepUpService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<SuperAdminAccountResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.success("SuperAdmins fetched", accountService.list()));
    }

    @PostMapping("/{publicId}/mfa/reset")
    public ResponseEntity<ApiResponse<SuperAdminAccountResponse>> resetMfa(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal SuperAdmin superAdmin,
            @RequestHeader(value = SuperAdminStepUpService.MFA_CODE_HEADER, required = false) String mfaCode,
            HttpServletRequest request) {
        String clientIp = ClientIp.resolve(request);
        String userAgent = request.getHeader("User-Agent");
        superAdminStepUpService.requireCode(superAdmin, mfaCode, "reset SuperAdmin MFA", clientIp, userAgent);
        return ResponseEntity.ok(ApiResponse.success(
                "SuperAdmin MFA reset",
                accountService.resetMfa(publicId, superAdmin, clientIp, userAgent)));
    }
}
