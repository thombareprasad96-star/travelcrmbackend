package com.crm.travelcrm.permission.dto;

import lombok.Builder;
import lombok.Value;

import java.util.Map;
import java.util.UUID;

// Per-user permission map. The full page catalog lives in the frontend; the
// backend only stores/returns the keyed { pageId: {access, scope} } map.
@Value
@Builder
public class UserPermissionsDTO {
    UUID   userPublicId;
    int    pages;   // count of pages with access=true
    int    total;   // count of stored entries
    Map<String, PermissionEntry> permissions;
}