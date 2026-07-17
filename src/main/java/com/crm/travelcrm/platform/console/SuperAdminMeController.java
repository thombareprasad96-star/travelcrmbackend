package com.crm.travelcrm.platform.console;

import com.crm.travelcrm.auth.dto.ChangePasswordRequest;
import com.crm.travelcrm.auth.repository.SuperAdminRepository;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.entity.SuperAdmin;
import com.crm.travelcrm.common.exception.BusinessException;
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

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<SuperAdminProfileDTO>> me() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object principal = auth != null ? auth.getPrincipal() : null;

        if (!(principal instanceof SuperAdmin superAdmin)) {
            // Should be unreachable given the @PreAuthorize gate, but never leak a tenant
            // principal through a platform endpoint.
            throw new BusinessException("Not a platform session.", HttpStatus.FORBIDDEN);
        }

        SuperAdminProfileDTO dto = new SuperAdminProfileDTO(
                superAdmin.getPublicId(), superAdmin.getName(), superAdmin.getEmail());
        return ResponseEntity.ok(ApiResponse.success("OK", dto));
    }

    /**
     * Self-service password change for the platform SuperAdmin.
     *
     * <p>This exists because the account otherwise had NO way to rotate its password. The tenant
     * flow at {@code POST /api/auth/change-password} takes an {@code @AuthenticationPrincipal User}
     * and 401s a SuperAdmin principal outright, and PlatformUserController's reset-password acts on
     * tenant users, not on the platform account. So the password set by SUPER_ADMIN_PASSWORD at the
     * first boot was permanent: DataInitializer is a no-op once the row exists, which made changing
     * it a manual UPDATE against a BCrypt hash in psql.
     *
     * <p>Mirrors AuthServiceImpl.changePassword: re-load a managed entity keyed on the principal's
     * own id (never its email — a weaker key can only lose information on the highest-consequence
     * write in the app), verify the current password, and refuse a no-op change.
     */
    @PostMapping("/me/change-password")
    public ResponseEntity<ApiResponse<Void>> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object principal = auth != null ? auth.getPrincipal() : null;

        if (!(principal instanceof SuperAdmin principalAdmin)) {
            throw new BusinessException("Not a platform session.", HttpStatus.FORBIDDEN);
        }

        SuperAdmin superAdmin = superAdminRepository.findById(principalAdmin.getId())
                .orElseThrow(() -> new BusinessException("Account no longer exists.", HttpStatus.UNAUTHORIZED));

        if (!passwordEncoder.matches(request.getCurrentPassword(), superAdmin.getPassword())) {
            log.warn("SuperAdmin change-password rejected — wrong current password for {}", superAdmin.getEmail());
            throw new BusinessException("Current password is incorrect.", HttpStatus.BAD_REQUEST);
        }

        if (passwordEncoder.matches(request.getNewPassword(), superAdmin.getPassword())) {
            throw new BusinessException(
                    "New password must be different from the current password.", HttpStatus.BAD_REQUEST);
        }

        superAdmin.setPassword(passwordEncoder.encode(request.getNewPassword()));
        superAdminRepository.save(superAdmin);
        log.info("SuperAdmin password changed for {}", superAdmin.getEmail());

        return ResponseEntity.ok(ApiResponse.success("Password changed successfully"));
    }
}