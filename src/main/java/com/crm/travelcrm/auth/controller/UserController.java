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


    @GetMapping("/available")
    @PreAuthorize("hasAuthority('CRM_FULL')")
    public ResponseEntity<ApiResponse<List<UserDto>>> getAvailableUsers() {
        log.info("Fetching available users for assignment");
        try {
            // Fetch all active users for current tenant (excluding SuperAdmin)
            List<UserDto> users = userService.getAvailableUsers();
            log.info("Successfully retrieved {} users for assignment", users.size());
            // Return success response with list of users
            return ResponseEntity
                    .status(HttpStatus.OK)
                    .body(ApiResponse.success("Users retrieved successfully", users));
        } catch (Exception e) {
            log.error("Error fetching available users", e);
            // Return error response if something goes wrong
            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.failure("Failed to retrieve users", null));
        }
    }
}