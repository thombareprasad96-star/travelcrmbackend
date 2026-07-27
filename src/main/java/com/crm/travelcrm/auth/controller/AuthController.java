package com.crm.travelcrm.auth.controller;

import com.crm.travelcrm.auth.dto.ChangePasswordRequest;
import com.crm.travelcrm.auth.dto.LoginRequestDTO;
import com.crm.travelcrm.auth.dto.LoginResponseDTO;
import com.crm.travelcrm.auth.dto.SuperAdminMfaVerifyRequest;
import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.service.AuthService;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.util.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;

    @PostMapping("/superadmin/login")
    public ResponseEntity<LoginResponseDTO> superAdminLogin(
            @RequestBody LoginRequestDTO request, HttpServletRequest httpRequest) {

        log.info("SuperAdmin login request for {}", request.getEmail());

        return ResponseEntity.ok(
                authService.superAdminLogin(
                        request,
                        ClientIp.resolve(httpRequest),
                        httpRequest.getHeader("User-Agent"))
        );
    }

    @PostMapping("/superadmin/mfa/verify")
    public ResponseEntity<LoginResponseDTO> verifySuperAdminMfa(
            @Valid @RequestBody SuperAdminMfaVerifyRequest request,
            HttpServletRequest httpRequest) {

        return ResponseEntity.ok(
                authService.verifySuperAdminMfa(
                        request,
                        ClientIp.resolve(httpRequest),
                        httpRequest.getHeader("User-Agent"))
        );
    }

    @PostMapping("/user/login")
    public ResponseEntity<LoginResponseDTO> userLogin(
            @RequestBody LoginRequestDTO request, HttpServletRequest httpRequest) {

        log.info("User login request for {}", request.getEmail());

        return ResponseEntity.ok(
                authService.userLogin(request, ClientIp.resolve(httpRequest))
        );
    }

    // Self-service password change for the authenticated tenant user. This path is
    // permitAll in SecurityConfig, but JwtAuthFilter still populates the principal when a
    // valid Bearer token is present — so a null principal here means "no/invalid token"
    // (or a SuperAdmin, who has no self-service change-password flow) → 401.
    @PostMapping("/change-password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            @AuthenticationPrincipal User currentUser) {

        if (currentUser == null) {
            throw new BusinessException("Authentication required.", HttpStatus.UNAUTHORIZED);
        }

        authService.changePassword(
                currentUser,
                request.getCurrentPassword(),
                request.getNewPassword());

        return ResponseEntity.ok(ApiResponse.success("Password changed successfully"));
    }
}
