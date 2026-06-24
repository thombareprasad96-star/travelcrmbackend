package com.crm.travelcrm.permission.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

// One page's permission: whether the user can access it and over what data scope
// (own | team | all | none). Serialized inside the per-user permissions JSON map.
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PermissionEntry {
    private boolean access;
    private String  scope = "own";
}