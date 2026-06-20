package com.crm.travelcrm.auth.api;

import java.util.List;
import java.util.Optional;

/**
 * Public, read-only directory lookups over tenant users, for modules that need to
 * resolve other users (not the caller) — e.g. notification fan-out and email delivery.
 *
 * <p>Exposing these as a narrow interface keeps the {@code User} entity and
 * {@code UserRepository} private to the auth module, so the rules they encode
 * (active flag, role filtering) cannot be bypassed or duplicated elsewhere.
 */
public interface UserDirectory {

    /**
     * Email address of a user by internal id, or empty if the user does not exist
     * or has no email on file.
     */
    Optional<String> emailById(Long userId);

    /**
     * Internal ids of all active {@code TENANT_ADMIN} users in the given tenant.
     * Returns an empty list when {@code tenantId} is null. Caller-side filtering
     * (e.g. excluding the acting user) stays with the caller.
     */
    List<Long> activeAdminIds(Long tenantId);
}