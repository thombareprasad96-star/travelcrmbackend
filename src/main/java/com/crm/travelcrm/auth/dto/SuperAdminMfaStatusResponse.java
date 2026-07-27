package com.crm.travelcrm.auth.dto;

import java.time.LocalDateTime;

public record SuperAdminMfaStatusResponse(
        boolean enabled,
        LocalDateTime enabledAt,
        LocalDateTime lastUsedAt) {
}
