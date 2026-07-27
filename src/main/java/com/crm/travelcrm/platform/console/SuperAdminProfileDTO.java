package com.crm.travelcrm.platform.console;

import java.util.UUID;

/**
 * The authenticated platform SuperAdmin's own profile, returned by
 * {@code GET /api/super-admin/me} so the console can verify the session and render identity.
 * Externalises {@code publicId} only — never the internal Long PK.
 */
public record SuperAdminProfileDTO(
        UUID publicId,
        String name,
        String email,
        boolean mfaEnabled,
        boolean mustChangePassword,
        boolean setupComplete) {
}
