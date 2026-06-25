package com.crm.travelcrm.permission.controller;

import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.permission.enums.Permission;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The fine-grained permission catalog — served straight from the {@link Permission}
 * enum so it is the single source of truth. The frontend renders its permission grid
 * from this endpoint, so the FE and BE can never drift.
 *
 * Any authenticated user may read it (it is just metadata used to draw the grid;
 * who can actually <em>edit</em> permissions is still gated by USER_UPDATE on the
 * user-permission and template endpoints).
 */
@RestController
@RequestMapping("/api/permissions")
@RequiredArgsConstructor
public class PermissionCatalogController {

    public record PermissionItem(String key, String label) {}
    public record PermissionModule(String module, List<PermissionItem> permissions) {}

    @GetMapping("/catalog")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<PermissionModule>>> catalog() {
        List<PermissionModule> modules = Permission.groupedByModule().entrySet().stream()
                .map(e -> new PermissionModule(
                        e.getKey(),
                        e.getValue().stream()
                                .map(p -> new PermissionItem(p.key(), p.getLabel()))
                                .toList()))
                .toList();
        return ResponseEntity.ok(ApiResponse.success("Permission catalog retrieved successfully", modules));
    }

    /**
     * The CURRENT user's effective permission keys — role default overlaid with their
     * saved per-user map, exactly as {@code EffectivePermissionResolver} put them into
     * the security context. The frontend uses this to drive menus/buttons so per-user
     * customizations show in the UI (not just role defaults).
     */
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<Map<String, Object>>> myPermissions(Authentication authentication) {
        Set<String> keys = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(Permission::isValidKey)
                .collect(Collectors.toSet());

        boolean platformAdmin = authentication.getAuthorities().stream()
                .anyMatch(a -> "PLATFORM_ADMIN".equals(a.getAuthority()));

        Map<String, Object> body = new HashMap<>();
        body.put("permissions", keys);
        body.put("platformAdmin", platformAdmin);
        return ResponseEntity.ok(ApiResponse.success("Effective permissions retrieved successfully", body));
    }
}
