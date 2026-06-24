package com.crm.travelcrm.permission.dto;

import lombok.Data;

import java.util.Map;

// Full replace of a user's permission map (the page sends the whole map on save).
@Data
public class UpdatePermissionsRequest {
    private Map<String, PermissionEntry> permissions;
}