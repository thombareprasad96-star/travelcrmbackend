package com.crm.travelcrm.settings.controller;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.settings.dto.EmailConfigDTO;
import com.crm.travelcrm.settings.dto.EmailConfigRequest;
import com.crm.travelcrm.settings.dto.EmailStatsDTO;
import com.crm.travelcrm.settings.dto.SaveConfigResponse;
import com.crm.travelcrm.settings.dto.TestEmailRequest;
import com.crm.travelcrm.settings.dto.TestEmailResponse;
import com.crm.travelcrm.settings.service.EmailConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-tenant email/SMTP settings. Reads open to any tenant user; save/test require SETTINGS_MANAGE
 * (same authority CompanyController uses for edits). Tenant comes from the JWT principal.
 */
@RestController
@RequestMapping("/api/settings/email")
@RequiredArgsConstructor
public class EmailConfigController {

    private final EmailConfigService service;

    @GetMapping("/config")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<EmailConfigDTO>> getConfig(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.success(
                "Email configuration retrieved", service.getConfig(user.getTenantId())));
    }

    @PostMapping("/config")
    @PreAuthorize("hasAuthority('SETTINGS_MANAGE')")
    public ResponseEntity<ApiResponse<SaveConfigResponse>> saveConfig(
            @Valid @RequestBody EmailConfigRequest request,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.success(
                "Email configuration saved", service.save(request, user.getTenantId())));
    }

    @PostMapping("/test")
    @PreAuthorize("hasAuthority('SETTINGS_MANAGE')")
    public ResponseEntity<ApiResponse<TestEmailResponse>> testEmail(
            @Valid @RequestBody TestEmailRequest request,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.success(
                "Test email attempted", service.sendTest(request, user.getTenantId())));
    }

    @GetMapping("/stats")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<EmailStatsDTO>> getStats(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.success(
                "Email stats retrieved", service.getStats(user.getTenantId())));
    }
}