package com.crm.travelcrm.company.controller;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.company.dto.AiCreditsDTO;
import com.crm.travelcrm.company.dto.CompanyDTO;
import com.crm.travelcrm.company.dto.CompanyUpdateRequest;
import com.crm.travelcrm.company.dto.SubscriptionDTO;
import com.crm.travelcrm.company.service.CompanyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

// One company profile per tenant. Reads are open to any tenant user (CRM_FULL);
// edits/uploads are tenant-admin only (USER_UPDATE). tenant comes from the principal.
@RestController
@RequestMapping("/api/company")
@RequiredArgsConstructor
public class CompanyController {

    private final CompanyService companyService;

    @GetMapping
    @PreAuthorize("hasAuthority('CRM_FULL')")
    public ResponseEntity<ApiResponse<CompanyDTO>> get(@AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                "Company retrieved successfully", companyService.get(currentUser.getTenantId())));
    }

    @PutMapping
    @PreAuthorize("hasAuthority('USER_UPDATE')")
    public ResponseEntity<ApiResponse<CompanyDTO>> update(
            @Valid @RequestBody CompanyUpdateRequest request,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                "Company updated successfully", companyService.update(request, currentUser.getTenantId())));
    }

    @PostMapping("/logo")
    @PreAuthorize("hasAuthority('USER_UPDATE')")
    public ResponseEntity<ApiResponse<CompanyDTO>> uploadLogo(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                "Logo uploaded successfully", companyService.uploadLogo(file, currentUser.getTenantId())));
    }

    @PostMapping("/favicon")
    @PreAuthorize("hasAuthority('USER_UPDATE')")
    public ResponseEntity<ApiResponse<CompanyDTO>> uploadFavicon(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                "Favicon uploaded successfully", companyService.uploadFavicon(file, currentUser.getTenantId())));
    }

    @GetMapping("/subscription")
    @PreAuthorize("hasAuthority('CRM_FULL')")
    public ResponseEntity<ApiResponse<SubscriptionDTO>> getSubscription(
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                "Subscription retrieved successfully", companyService.getSubscription(currentUser.getTenantId())));
    }

    @GetMapping("/ai-credits")
    @PreAuthorize("hasAuthority('CRM_FULL')")
    public ResponseEntity<ApiResponse<AiCreditsDTO>> getAiCredits(
            @AuthenticationPrincipal User currentUser) {
        return ResponseEntity.ok(ApiResponse.success(
                "AI credits retrieved successfully", companyService.getAiCredits(currentUser.getTenantId())));
    }
}