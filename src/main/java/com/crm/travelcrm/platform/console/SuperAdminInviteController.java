package com.crm.travelcrm.platform.console;

import com.crm.travelcrm.auth.mfa.SuperAdminStepUpService;
import com.crm.travelcrm.auth.dto.SuperAdminInviteCreateRequest;
import com.crm.travelcrm.auth.dto.SuperAdminInviteResponse;
import com.crm.travelcrm.auth.service.SuperAdminInviteService;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.entity.SuperAdmin;
import com.crm.travelcrm.common.util.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/super-admin/invites")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class SuperAdminInviteController {

    private final SuperAdminInviteService inviteService;
    private final SuperAdminStepUpService superAdminStepUpService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<SuperAdminInviteResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.success("Invites fetched", inviteService.list()));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<SuperAdminInviteResponse>> create(
            @Valid @RequestBody SuperAdminInviteCreateRequest request,
            @AuthenticationPrincipal SuperAdmin superAdmin,
            @RequestHeader(value = SuperAdminStepUpService.MFA_CODE_HEADER, required = false) String mfaCode,
            HttpServletRequest httpRequest) {
        superAdminStepUpService.requireCode(
                superAdmin, mfaCode, "create SuperAdmin invite",
                ClientIp.resolve(httpRequest), httpRequest.getHeader("User-Agent"));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Invite created", inviteService.create(request)));
    }
}
