package com.crm.travelcrm.platform.console;

import com.crm.travelcrm.auth.dto.ChangePasswordRequest;
import com.crm.travelcrm.auth.dev.DevSuperAdminLoginMode;
import com.crm.travelcrm.auth.mfa.SuperAdminStepUpService;
import com.crm.travelcrm.auth.repository.SuperAdminRepository;
import com.crm.travelcrm.auth.security.SuperAdminPasswordPolicy;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.entity.SuperAdmin;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.common.util.ClientIp;
import com.crm.travelcrm.platform.audit.PlatformAuditRecorder;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditAction;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Console session bootstrap for the platform SuperAdmin. Guarded by {@code ROLE_SUPER_ADMIN},
 * so a tenant token can never reach it. Returns the caller's own profile (publicId only).
 */
@Slf4j
@RestController
@RequestMapping("/api/super-admin")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class SuperAdminMeController {

    private final SuperAdminRepository superAdminRepository;
    private final PasswordEncoder passwordEncoder;
    private final PlatformAuditRecorder platformAuditRecorder;
    private final SuperAdminPasswordPolicy superAdminPasswordPolicy;
    private final SuperAdminStepUpService superAdminStepUpService;
    private final DevSuperAdminLoginMode devLoginMode;

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<SuperAdminProfileDTO>> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object principal = auth != null ? auth.getPrincipal() : null;

        if (!(principal instanceof SuperAdmin superAdmin)) {
            // Should be unreachable given the @PreAuthorize gate, but never leak a tenant
            // principal through a platform endpoint.
            throw new BusinessException("Not a platform session.", HttpStatus.FORBIDDEN);
        }

        // isSetupComplete() is "MFA enrolled AND password changed" — unreachable on a login path
        // that skips MFA. The console hard-redirects to /console/setup while this is false, so on
        // the local dev bypass it would park on a setup screen that can never be satisfied.
        // Non-prod only; see DevSuperAdminLoginMode. mfaEnabled below stays the truth.
        boolean setupComplete = devLoginMode.isEnabled() || superAdmin.isSetupComplete();

        SuperAdminProfileDTO dto = new SuperAdminProfileDTO(
                superAdmin.getPublicId(),
                superAdmin.getName(),
                superAdmin.getEmail(),
                superAdmin.isMfaEnabled(),
                superAdmin.isMustChangePassword(),
                setupComplete);
        return ResponseEntity.ok(ApiResponse.success("OK", dto));
    }

    /**
     * Self-service password change for the platform SuperAdmin.
     *
     * <p>This exists because the account otherwise had NO way to rotate its password. The tenant
     * flow at {@code POST /api/auth/change-password} takes an {@code @AuthenticationPrincipal User}
     * and 401s a SuperAdmin principal outright, and PlatformUserController's reset-password acts on
     * tenant users, not on the platform account. So a bootstrap password from
     * SUPERADMIN_1_PASSWORD or SUPERADMIN_2_PASSWORD was otherwise permanent once the row existed.
     *
     * <p>Mirrors AuthServiceImpl.changePassword: re-load a managed entity keyed on the principal's
     * own id (never its email — a weaker key can only lose information on the highest-consequence
     * write in the app), verify the current password, and refuse a no-op change.
     */
    @PostMapping("/me/change-password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            @RequestHeader(value = SuperAdminStepUpService.MFA_CODE_HEADER, required = false) String mfaCode,
            HttpServletRequest httpRequest) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object principal = auth != null ? auth.getPrincipal() : null;

        if (!(principal instanceof SuperAdmin principalAdmin)) {
            throw new BusinessException("Not a platform session.", HttpStatus.FORBIDDEN);
        }

        superAdminStepUpService.requireCode(
                principalAdmin, mfaCode, "SuperAdmin password change",
                ClientIp.resolve(httpRequest), httpRequest.getHeader("User-Agent"));

        SuperAdmin superAdmin = superAdminRepository.findById(principalAdmin.getId())
                .orElseThrow(() -> new BusinessException("Account no longer exists.", HttpStatus.UNAUTHORIZED));

        superAdminPasswordPolicy.validate(request.getNewPassword());

        if (!passwordEncoder.matches(request.getCurrentPassword(), superAdmin.getPassword())) {
            log.warn("SuperAdmin change-password rejected — wrong current password for {}", superAdmin.getEmail());
            throw new BusinessException("Current password is incorrect.", HttpStatus.BAD_REQUEST);
        }

        if (passwordEncoder.matches(request.getNewPassword(), superAdmin.getPassword())) {
            throw new BusinessException(
                    "New password must be different from the current password.", HttpStatus.BAD_REQUEST);
        }

        superAdmin.setPassword(passwordEncoder.encode(request.getNewPassword()));
        superAdmin.setMustChangePassword(false);
        superAdmin.bumpTokenVersion();
        superAdminRepository.save(superAdmin);
        log.info("SuperAdmin password changed for {}", superAdmin.getEmail());

        platformAuditRecorder.safeRecord(
                PlatformAuditAction.SUPER_ADMIN_PASSWORD_CHANGE, true,
                null, null, "SUPER_ADMIN", superAdmin.getPublicId(),
                "SuperAdmin changed own password; existing sessions revoked");

        return ResponseEntity.ok(ApiResponse.success("Password changed successfully"));
    }
}
