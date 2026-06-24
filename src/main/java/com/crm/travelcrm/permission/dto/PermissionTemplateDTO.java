package com.crm.travelcrm.permission.dto;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Value
@Builder
public class PermissionTemplateDTO {
    UUID    publicId;
    String  value;
    String  label;
    String  description;
    int     pages;        // count of pages with access=true
    int     usersCount;   // reserved (templates are not yet linked to users) — always 0
    boolean isDefault;
    Map<String, PermissionEntry> permissions;
    LocalDateTime createdAt;
}