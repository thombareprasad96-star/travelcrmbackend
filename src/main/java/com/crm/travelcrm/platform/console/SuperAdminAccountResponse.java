package com.crm.travelcrm.platform.console;

import java.time.LocalDateTime;
import java.util.UUID;

public record SuperAdminAccountResponse(
        UUID publicId,
        String name,
        String email,
        boolean enabled,
        boolean mfaEnabled,
        boolean mustChangePassword,
        boolean setupComplete,
        boolean createdViaInvite,
        boolean locked,
        Integer failedLoginAttempts,
        LocalDateTime lockedUntil,
        LocalDateTime lastLoginAt,
        String lastLoginIp,
        LocalDateTime createdAt) {
}
