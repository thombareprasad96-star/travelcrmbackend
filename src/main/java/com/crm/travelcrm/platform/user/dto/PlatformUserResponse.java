package com.crm.travelcrm.platform.user.dto;

import com.crm.travelcrm.auth.enums.Role;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A tenant user as seen by the SuperAdmin (cross-tenant). Externalises {@code publicId} only and
 * never the password. Carries the owning tenant's code/name for display.
 */
@Data
@Builder
public class PlatformUserResponse {
    private UUID publicId;
    private String name;
    private String email;
    private Role role;
    private String phoneNumber;
    private String tenantCode;
    private String tenantName;
    private boolean active;   // isActive (tenant-admin toggle)
    private boolean locked;   // SuperAdmin lock
    private LocalDateTime lockedAt;
    private String lockedBy;
    private LocalDateTime createdAt;
}