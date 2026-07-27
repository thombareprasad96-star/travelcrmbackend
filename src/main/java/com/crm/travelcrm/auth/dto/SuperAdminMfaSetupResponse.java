package com.crm.travelcrm.auth.dto;

public record SuperAdminMfaSetupResponse(
        boolean enabled,
        String issuer,
        String manualEntryKey,
        String otpAuthUri) {
}
