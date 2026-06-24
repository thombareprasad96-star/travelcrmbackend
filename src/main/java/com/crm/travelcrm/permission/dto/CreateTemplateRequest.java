package com.crm.travelcrm.permission.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;
import java.util.UUID;

// Create a reusable template. Permissions are resolved in this order:
//   1. explicit `permissions` map, else
//   2. copied from `copyFromUserPublicId`'s saved permissions, else
//   3. empty (blank template).
@Data
public class CreateTemplateRequest {

    @NotBlank(message = "Template name is required")
    @Size(min = 3, max = 150, message = "Template name must be 3–150 characters")
    private String label;

    // Optional stable key; defaults to a slug of the label.
    private String value;

    @Size(max = 500, message = "Description must be 500 characters or fewer")
    private String description;

    private Boolean isDefault;

    private UUID copyFromUserPublicId;

    private Map<String, PermissionEntry> permissions;
}