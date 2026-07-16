package com.crm.travelcrm.accounting.tax.controller;

import com.crm.travelcrm.accounting.tax.dto.HsnSacRateDto;
import com.crm.travelcrm.accounting.tax.dto.HsnSacRateRequest;
import com.crm.travelcrm.accounting.tax.service.HsnSacRateService;
import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/accounting/hsn-rates")
@RequiredArgsConstructor
public class HsnSacRateController {

    private final HsnSacRateService service;

    @GetMapping
    @PreAuthorize("hasAuthority('ACCOUNTING_INVOICE_READ')")
    public ResponseEntity<ApiResponse<List<HsnSacRateDto>>> getAll(
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                "HSN/SAC rates retrieved", service.getAll(currentUser.getTenantId())));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('ACCOUNTING_SETTINGS_MANAGE')")
    public ResponseEntity<ApiResponse<HsnSacRateDto>> create(
            @Valid @RequestBody HsnSacRateRequest request,
            @AuthenticationPrincipal User currentUser) {
        HsnSacRateDto created = service.create(request, currentUser.getTenantId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("HSN/SAC rate created", created, 201));
    }

    @PutMapping("/{publicId}")
    @PreAuthorize("hasAuthority('ACCOUNTING_SETTINGS_MANAGE')")
    public ResponseEntity<ApiResponse<HsnSacRateDto>> update(
            @PathVariable UUID publicId,
            @Valid @RequestBody HsnSacRateRequest request,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                "HSN/SAC rate updated", service.update(publicId, request, currentUser.getTenantId())));
    }

    @DeleteMapping("/{publicId}")
    @PreAuthorize("hasAuthority('ACCOUNTING_SETTINGS_MANAGE')")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal User currentUser) {
        service.delete(publicId, currentUser.getTenantId(), currentUser.getEmail());
        return ResponseEntity.ok(ApiResponse.success("HSN/SAC rate deleted"));
    }
}