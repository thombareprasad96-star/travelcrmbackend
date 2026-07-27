package com.crm.travelcrm.platform.user.controller;

import com.crm.travelcrm.auth.mfa.SuperAdminStepUpService;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.dto.PagedApiResponse;
import com.crm.travelcrm.common.dto.PaginationMeta;
import com.crm.travelcrm.common.entity.SuperAdmin;
import com.crm.travelcrm.common.util.ClientIp;
import com.crm.travelcrm.platform.user.dto.PlatformResetPasswordRequest;
import com.crm.travelcrm.platform.user.dto.PlatformUserResponse;
import com.crm.travelcrm.platform.user.service.PlatformUserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Cross-tenant user control for the SuperAdmin. Keyed on {@code publicId}; guarded by
 * {@code ROLE_SUPER_ADMIN}. Distinct from the tenant-scoped {@code /api/users/**} surface.
 */
@RestController
@RequestMapping("/api/super-admin/users")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class PlatformUserController {

    private final PlatformUserService platformUserService;
    private final SuperAdminStepUpService superAdminStepUpService;

    @GetMapping
    public ResponseEntity<PagedApiResponse<PlatformUserResponse>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID tenantId,   // tenant publicId (optional filter)
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<PlatformUserResponse> result = platformUserService.listUsers(search, tenantId, pageable);
        return ResponseEntity.ok(PagedApiResponse.of(
                "Users fetched", result.getContent(), PaginationMeta.from(result)));
    }

    @PostMapping("/{publicId}/lock")
    public ResponseEntity<ApiResponse<PlatformUserResponse>> lock(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal SuperAdmin superAdmin,
            @RequestHeader(value = SuperAdminStepUpService.MFA_CODE_HEADER, required = false) String mfaCode,
            HttpServletRequest request) {
        requireStepUp(superAdmin, mfaCode, "lock tenant user", request);
        return ResponseEntity.ok(ApiResponse.success("User locked", platformUserService.lock(publicId)));
    }

    @PostMapping("/{publicId}/unlock")
    public ResponseEntity<ApiResponse<PlatformUserResponse>> unlock(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal SuperAdmin superAdmin,
            @RequestHeader(value = SuperAdminStepUpService.MFA_CODE_HEADER, required = false) String mfaCode,
            HttpServletRequest request) {
        requireStepUp(superAdmin, mfaCode, "unlock tenant user", request);
        return ResponseEntity.ok(ApiResponse.success("User unlocked", platformUserService.unlock(publicId)));
    }

    @PostMapping("/{publicId}/reset-password")
    public ResponseEntity<ApiResponse<PlatformUserResponse>> resetPassword(
            @PathVariable UUID publicId,
            @Valid @RequestBody PlatformResetPasswordRequest request,
            @AuthenticationPrincipal SuperAdmin superAdmin,
            @RequestHeader(value = SuperAdminStepUpService.MFA_CODE_HEADER, required = false) String mfaCode,
            HttpServletRequest httpRequest) {
        requireStepUp(superAdmin, mfaCode, "force-reset tenant user password", httpRequest);
        return ResponseEntity.ok(ApiResponse.success(
                "Password reset", platformUserService.resetPassword(publicId, request.getNewPassword())));
    }

    private void requireStepUp(SuperAdmin superAdmin, String mfaCode, String action,
                               HttpServletRequest request) {
        superAdminStepUpService.requireCode(
                superAdmin, mfaCode, action,
                ClientIp.resolve(request), request.getHeader("User-Agent"));
    }
}
