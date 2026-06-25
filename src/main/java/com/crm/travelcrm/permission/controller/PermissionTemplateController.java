package com.crm.travelcrm.permission.controller;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.common.dto.ApiResponse;
import com.crm.travelcrm.permission.dto.CreateTemplateRequest;
import com.crm.travelcrm.permission.dto.PermissionTemplateDTO;
import com.crm.travelcrm.permission.service.PermissionTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// Reusable permission templates, scoped to the caller's tenant.
@RestController
@RequestMapping("/api/permission-templates")
@RequiredArgsConstructor
public class PermissionTemplateController {

    private final PermissionTemplateService templateService;

    @GetMapping
    @PreAuthorize("hasAuthority('USER_READ')")
    public ResponseEntity<ApiResponse<List<PermissionTemplateDTO>>> list(
            @AuthenticationPrincipal User currentUser) {

        List<PermissionTemplateDTO> templates = templateService.list(currentUser.getTenantId());
        return ResponseEntity.ok(ApiResponse.success("Templates retrieved successfully", templates));
    }

    @GetMapping("/{value}")
    @PreAuthorize("hasAuthority('USER_READ')")
    public ResponseEntity<ApiResponse<PermissionTemplateDTO>> getByValue(
            @PathVariable String value,
            @AuthenticationPrincipal User currentUser) {

        PermissionTemplateDTO template = templateService.getByValue(value, currentUser.getTenantId());
        return ResponseEntity.ok(ApiResponse.success("Template retrieved successfully", template));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('USER_UPDATE')")
    public ResponseEntity<ApiResponse<PermissionTemplateDTO>> create(
            @Valid @RequestBody CreateTemplateRequest request,
            @AuthenticationPrincipal User currentUser) {

        PermissionTemplateDTO created = templateService.create(request, currentUser.getTenantId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Template created successfully", created, 201));
    }

    @PutMapping("/{value}")
    @PreAuthorize("hasAuthority('USER_UPDATE')")
    public ResponseEntity<ApiResponse<PermissionTemplateDTO>> update(
            @PathVariable String value,
            @Valid @RequestBody CreateTemplateRequest request,
            @AuthenticationPrincipal User currentUser) {

        PermissionTemplateDTO updated = templateService.update(value, request, currentUser.getTenantId());
        return ResponseEntity.ok(ApiResponse.success("Template updated successfully", updated));
    }

    @DeleteMapping("/{value}")
    @PreAuthorize("hasAuthority('USER_UPDATE')")
    public ResponseEntity<ApiResponse<Void>> delete(
            @PathVariable String value,
            @AuthenticationPrincipal User currentUser) {

        templateService.delete(value, currentUser.getTenantId());
        return ResponseEntity.ok(ApiResponse.success("Template deleted successfully"));
    }
}