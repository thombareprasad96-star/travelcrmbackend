package com.crm.travelcrm.settings.controller;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.settings.dto.SaveConfigResponse;
import com.crm.travelcrm.settings.dto.TestWhatsAppRequest;
import com.crm.travelcrm.settings.dto.TestWhatsAppResponse;
import com.crm.travelcrm.settings.dto.WhatsAppConfigDTO;
import com.crm.travelcrm.settings.dto.WhatsAppConfigRequest;
import com.crm.travelcrm.settings.dto.WhatsAppStatsDTO;
import com.crm.travelcrm.settings.service.WhatsAppConfigService;
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
 * Per-tenant WhatsApp integration settings. Reads open to any tenant user; save/test require
 * SETTINGS_MANAGE. Tenant comes from the JWT principal.
 */
@RestController
@RequestMapping("/api/settings/whatsapp")
@RequiredArgsConstructor
public class WhatsAppConfigController {

    private final WhatsAppConfigService service;

    @GetMapping("/config")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<WhatsAppConfigDTO>> getConfig(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.success(
                "WhatsApp configuration retrieved", service.getConfig(user.getTenantId())));
    }

    @PostMapping("/config")
    @PreAuthorize("hasAuthority('SETTINGS_MANAGE')")
    public ResponseEntity<ApiResponse<SaveConfigResponse>> saveConfig(
            @Valid @RequestBody WhatsAppConfigRequest request,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.success(
                "WhatsApp configuration saved", service.save(request, user.getTenantId())));
    }

    @PostMapping("/test")
    @PreAuthorize("hasAuthority('SETTINGS_MANAGE')")
    public ResponseEntity<ApiResponse<TestWhatsAppResponse>> testMessage(
            @Valid @RequestBody TestWhatsAppRequest request,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.success(
                "Test message attempted", service.sendTest(request, user.getTenantId())));
    }

    @GetMapping("/stats")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<WhatsAppStatsDTO>> getStats(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(ApiResponse.success(
                "WhatsApp stats retrieved", service.getStats(user.getTenantId())));
    }
}