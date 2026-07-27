package com.crm.travelcrm.auth.dto;

import java.time.LocalDateTime;

public record SuperAdminInviteSetupResponse(
        String name,
        String email,
        LocalDateTime expiresAt,
        String mfaIssuer,
        String mfaManualEntryKey,
        String mfaOtpAuthUri) {
}
