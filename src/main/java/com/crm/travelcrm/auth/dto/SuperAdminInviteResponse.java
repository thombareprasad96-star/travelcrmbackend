package com.crm.travelcrm.auth.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record SuperAdminInviteResponse(
        UUID publicId,
        String name,
        String email,
        LocalDateTime expiresAt,
        LocalDateTime consumedAt,
        String token,
        String acceptUrl) {
}
