package com.crm.travelcrm.portal.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Resolves the authenticated {@link TravelerPrincipal} from the security context. Every portal
 * service calls {@link #require()} and scopes its queries by {@code customerId}/{@code tenantId}
 * from it — this is the object-level ownership key, so a traveler can only ever reach their own
 * customer's data. Fails closed (401-style) if somehow called without a traveler principal.
 */
public final class CurrentTraveler {

    private CurrentTraveler() {}

    public static TravelerPrincipal require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof TravelerPrincipal p) {
            return p;
        }
        throw new IllegalStateException("No authenticated traveler in the security context.");
    }
}
