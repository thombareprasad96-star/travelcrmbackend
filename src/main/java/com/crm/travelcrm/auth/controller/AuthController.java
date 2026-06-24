package com.crm.travelcrm.auth.controller;

import com.crm.travelcrm.auth.dto.ChangePasswordRequest;
import com.crm.travelcrm.auth.dto.LoginRequestDTO;
import com.crm.travelcrm.auth.dto.LoginResponseDTO;
import com.crm.travelcrm.auth.dto.RegisterRequestDTO;
import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.service.AuthService;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.util.ClientIp;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${superadmin.signup-secret}")
    private String signupSecret;

    @PostMapping("/superadmin/signup")

    public ResponseEntity<String> registerSuperAdmin(
            @Valid @RequestBody RegisterRequestDTO request,
            @RequestHeader(value = "X-Signup-Secret", required = false) String secret) {

        if (secret == null || !constantTimeEquals(secret, signupSecret)) {
            log.warn("Invalid signup secret used");
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body("Invalid signup secret");
        }

        log.info("SuperAdmin signup request received for {}", request.getEmail());

        return authService.registerSuperAdmin(request);
    }

    @PostMapping("/superadmin/login")
    public ResponseEntity<LoginResponseDTO> superAdminLogin(
            @RequestBody LoginRequestDTO request) {

        log.info("SuperAdmin login request for {}", request.getEmail());

        return ResponseEntity.ok(
                authService.superAdminLogin(request)
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

    /** Constant-time comparison so the signup secret can't be guessed via response timing. */
    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
                a.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                b.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}