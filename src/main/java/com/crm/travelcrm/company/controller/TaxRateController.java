package com.crm.travelcrm.company.controller;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.company.dto.TaxRateCreateRequest;
import com.crm.travelcrm.company.dto.TaxRateDTO;
import com.crm.travelcrm.company.service.TaxRateService;
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
@RequestMapping("/api/tax-rates")
@RequiredArgsConstructor
public class TaxRateController {

    private final TaxRateService taxRateService;

    @GetMapping
    @PreAuthorize("hasAuthority('CRM_FULL')")
    public ResponseEntity<ApiResponse<List<TaxRateDTO>>> getAll(
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                "Tax rates retrieved successfully", taxRateService.getAll(currentUser.getTenantId())));
    }

    @GetMapping("/active")
    @PreAuthorize("hasAuthority('CRM_FULL')")
    public ResponseEntity<ApiResponse<List<TaxRateDTO>>> getActive(
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                "Active tax rates retrieved successfully", taxRateService.getActive(currentUser.getTenantId())));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('USER_UPDATE')")
    public ResponseEntity<ApiResponse<TaxRateDTO>> create(
            @Valid @RequestBody TaxRateCreateRequest request,
            @AuthenticationPrincipal User currentUser) {
        TaxRateDTO created = taxRateService.create(request, currentUser.getTenantId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Tax rate created successfully", created, 201));
    }

    @DeleteMapping("/{publicId}")
    @PreAuthorize("hasAuthority('USER_UPDATE')")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal User currentUser) {
        taxRateService.delete(publicId, currentUser.getTenantId());
        return ResponseEntity.ok(ApiResponse.success("Tax rate deleted successfully"));
    }
}