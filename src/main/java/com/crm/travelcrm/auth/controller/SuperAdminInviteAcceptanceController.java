package com.crm.travelcrm.auth.controller;

import com.crm.travelcrm.auth.dto.LoginResponseDTO;
import com.crm.travelcrm.auth.dto.SuperAdminInviteAcceptRequest;
import com.crm.travelcrm.auth.dto.SuperAdminInviteSetupResponse;
import com.crm.travelcrm.auth.service.SuperAdminInviteService;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.util.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/superadmin/invites")
@RequiredArgsConstructor
public class SuperAdminInviteAcceptanceController {

    private final SuperAdminInviteService inviteService;

    @GetMapping("/{token}/setup")
    public ResponseEntity<ApiResponse<SuperAdminInviteSetupResponse>> setup(@PathVariable String token) {
        return ResponseEntity.ok(ApiResponse.success("Invite setup fetched", inviteService.setup(token)));
    }

    @PostMapping("/{token}/accept")
    public ResponseEntity<ApiResponse<LoginResponseDTO>> accept(
            @PathVariable String token,
            @Valid @RequestBody SuperAdminInviteAcceptRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(ApiResponse.success(
                "Invite accepted",
                inviteService.accept(
                        request,
                        token,
                        ClientIp.resolve(httpRequest),
                        httpRequest.getHeader("User-Agent"))));
    }
}
