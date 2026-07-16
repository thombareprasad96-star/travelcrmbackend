package com.crm.travelcrm.auth.controller;

import com.crm.travelcrm.auth.dto.MeProfileResponse;
import com.crm.travelcrm.auth.dto.UpdateMeProfileRequest;
import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.service.MeProfileService;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.exception.BusinessException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Self-service profile for the authenticated tenant user: view own account + edit own name/phone.
 * Email and role are read-only; password changes go through {@code /api/auth/change-password}.
 * Available to every tenant user — a B2B SUB_AGENT manages their own profile here since they cannot
 * reach the company-level profile/settings pages.
 */
@RestController
@RequestMapping("/api/me/profile")
@RequiredArgsConstructor
public class MeProfileController {

    private final MeProfileService meProfileService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<MeProfileResponse>> get(@AuthenticationPrincipal User user) {
        requireUser(user);
        return ResponseEntity.ok(ApiResponse.success("Profile fetched", meProfileService.getMyProfile(user)));
    }

    @PutMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<MeProfileResponse>> update(
            @AuthenticationPrincipal User user, @Valid @RequestBody UpdateMeProfileRequest request) {
        requireUser(user);
        return ResponseEntity.ok(ApiResponse.success("Profile updated", meProfileService.updateMyProfile(user, request)));
    }

    private void requireUser(User user) {
        if (user == null) {
            throw new BusinessException("Authentication required.", HttpStatus.UNAUTHORIZED);
        }
    }
}