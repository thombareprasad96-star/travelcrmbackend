package com.crm.travelcrm.portal.security;

import java.security.Principal;
import java.util.UUID;

/**
 * The authenticated traveler set into the {@code SecurityContext} on the portal chain. This is a
 * deliberately separate principal type from the staff {@code User} — a portal controller that casts
 * {@code auth.getPrincipal()} to {@code User} would fail, which is by design: the two realms never
 * share a principal. Carries the ids every portal query scopes by (own customer + tenant).
 *
 * @param accountId        internal TravelerAccount.id (for updates within a request)
 * @param accountPublicId  the account's UUID (what the token's subject carries)
 * @param customerId       internal Customer.id — the object-level ownership key for every query
 * @param customerPublicId the customer's UUID (safe to echo back to the client)
 * @param tenantId         owning tenant
 * @param name             customer display name
 */
public record TravelerPrincipal(
        Long accountId,
        UUID accountPublicId,
        Long customerId,
        UUID customerPublicId,
        Long tenantId,
        String name
) implements Principal {

    /** Authority granted to every traveler — distinct from any staff authority. */
    public static final String AUTHORITY = "PORTAL_TRAVELER";

    /**
     * Used by Spring Security's {@code Authentication.getName()} and thus the JPA auditor — returns
     * a stable, non-leaky tag (the customer's UUID, never the internal Long id) so traveler-written
     * rows get a clean {@code created_by}/{@code updated_by} like {@code traveler:<uuid>}.
     */
    @Override
    public String getName() {
        return "traveler:" + customerPublicId;
    }
}
