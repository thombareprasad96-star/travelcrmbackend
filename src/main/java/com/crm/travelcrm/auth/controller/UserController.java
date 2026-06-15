package com.crm.travelcrm.auth.controller;

import com.crm.travelcrm.auth.dto.CreateUserRequest;
import com.crm.travelcrm.auth.dto.UpdateUserRequest;
import com.crm.travelcrm.auth.dto.UserDto;
import com.crm.travelcrm.auth.dto.UserResponseDTO;
import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.service.UserService;
import com.crm.travelcrm.common.dto.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Slf4j
public class UserController {

    private final UserService userService;

    // Creates a MANAGER or AGENT inside the caller's own tenant.
    // USER_CREATE is granted to TENANT_ADMIN only (see Role.authorities()).
    // The tenant is taken from the authenticated principal — never the request body —
    // so a tenant admin can never provision a user into a foreign tenant. Role is
    // validated server-side in UserServiceImpl (SUPERADMIN / TENANT_ADMIN are rejected).
    @PostMapping
    @PreAuthorize("hasAuthority('USER_CREATE')")
    public ResponseEntity<ApiResponse<UserResponseDTO>> createUser(
            @Valid @RequestBody CreateUserRequest request,
            @AuthenticationPrincipal User currentUser) {

        UserResponseDTO created = userService.createUser(request, currentUser.getTenantId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("User created successfully", created));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('USER_READ')")
    public ResponseEntity<ApiResponse<List<UserResponseDTO>>> listUsers(
            @AuthenticationPrincipal User currentUser) {

        List<UserResponseDTO> users = userService.getUsersByTenant(currentUser.getTenantId());
        return ResponseEntity.ok(ApiResponse.success("Users retrieved successfully", users));
    }

    @PutMapping("/{publicId}")
    @PreAuthorize("hasAuthority('USER_UPDATE')")
    public ResponseEntity<ApiResponse<UserResponseDTO>> updateUser(
            @PathVariable UUID publicId,
            @Valid @RequestBody UpdateUserRequest request,
            @AuthenticationPrincipal User currentUser) {

        UserResponseDTO updated = userService.updateUser(publicId, request, currentUser.getTenantId());
        return ResponseEntity.ok(ApiResponse.success("User updated successfully", updated));
    }

    @DeleteMapping("/{publicId}")
    @PreAuthorize("hasAuthority('USER_DELETE')")
    public ResponseEntity<ApiResponse<Void>> deleteUser(
            @PathVariable UUID publicId,
            @AuthenticationPrincipal User currentUser) {

        userService.deleteUser(publicId, currentUser.getTenantId(), currentUser.getEmail());
        return ResponseEntity.ok(ApiResponse.success("User deleted successfully"));
    }


    // Active tenant users eligible for lead assignment.
    // Errors propagate to GlobalExceptionHandler — no local try/catch,
    // otherwise auth failures surface as misleading 500s.
    @GetMapping("/available")
    @PreAuthorize("hasAuthority('CRM_FULL')")
    public ResponseEntity<ApiResponse<List<UserDto>>> getAvailableUsers() {
        List<UserDto> users = userService.getAvailableUsers();
        return ResponseEntity.ok(ApiResponse.success("Users retrieved successfully", users));
    }
}