package com.crm.travelcrm.accounting.settings.controller;

import com.crm.travelcrm.accounting.settings.dto.AccountingSettingsDto;
import com.crm.travelcrm.accounting.settings.dto.UpdateAccountingSettingsRequest;
import com.crm.travelcrm.accounting.settings.service.AccountingSettingsService;
import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/accounting/settings")
@RequiredArgsConstructor
public class AccountingSettingsController {

    private final AccountingSettingsService service;

    @GetMapping
    @PreAuthorize("hasAuthority('ACCOUNTING_INVOICE_READ')")
    public ResponseEntity<ApiResponse<AccountingSettingsDto>> get(
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                "Accounting settings retrieved", service.get(currentUser.getTenantId())));
    }

    @PutMapping
    @PreAuthorize("hasAuthority('ACCOUNTING_SETTINGS_MANAGE')")
    public ResponseEntity<ApiResponse<AccountingSettingsDto>> update(
            @Valid @RequestBody UpdateAccountingSettingsRequest request,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                "Accounting settings updated", service.update(request, currentUser.getTenantId())));
    }
}