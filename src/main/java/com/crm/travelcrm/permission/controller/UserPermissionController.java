package com.crm.travelcrm.permission.controller;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.permission.dto.UpdatePermissionsRequest;
import com.crm.travelcrm.permission.dto.UserPermissionsDTO;
import com.crm.travelcrm.permission.service.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

// Per-user permission map. Keyed by the user's publicId; tenant comes from the
// authenticated principal. USER_READ/USER_UPDATE are granted to TENANT_ADMIN only.
@RestController
@RequestMapping("/api/users/{publicId}/permissions")
@RequiredArgsConstructor
public class UserPermissionController {

    private final PermissionService permissionService;

    @GetMapping
    @PreAuthorize("hasAuthority('USER_READ')")
    public ResponseEntity<ApiResponse<UserPermissionsDTO>> get(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal User currentUser) {

        UserPermissionsDTO dto = permissionService.getForUser(publicId, currentUser.getTenantId());
        return ResponseEntity.ok(ApiResponse.success("Permissions retrieved successfully", dto));
    }

    @PutMapping
    @PreAuthorize("hasAuthority('USER_UPDATE')")
    public ResponseEntity<ApiResponse<UserPermissionsDTO>> update(
            @PathVariable UUID publicId,
            @RequestBody UpdatePermissionsRequest request,
            @AuthenticationPrincipal User currentUser) {

        UserPermissionsDTO dto = permissionService.save(
                publicId, request.getPermissions(), currentUser.getTenantId());
        return ResponseEntity.ok(ApiResponse.success("Permissions updated successfully", dto));
    }
}