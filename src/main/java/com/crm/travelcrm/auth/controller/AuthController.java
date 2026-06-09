package com.crm.travelcrm.auth.controller;

import com.crm.travelcrm.auth.dto.LoginRequestDTO;
import com.crm.travelcrm.auth.dto.LoginResponseDTO;
import com.crm.travelcrm.auth.dto.RegisterRequestDTO;
import com.crm.travelcrm.auth.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
            @RequestBody RegisterRequestDTO request,
            @RequestHeader(value = "X-Signup-Secret", required = false) String secret) {

        if (secret == null || !secret.equals(signupSecret)) {
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
            @RequestBody LoginRequestDTO request) {

        log.info("User login request for {}", request.getEmail());

        return ResponseEntity.ok(
                authService.userLogin(request)
        );
    }
}