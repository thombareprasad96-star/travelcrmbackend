package com.crm.travelcrm.platform.console;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.common.entity.SuperAdmin;
import com.crm.travelcrm.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
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
public class SuperAdminMeController {

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
}