package com.crm.travelcrm.auth.security;

/**
 * Single source of truth for JWT claim names and well-known claim values, shared by the
 * token producer ({@link JwtUtil}) and the token consumer ({@link JwtAuthFilter}). Keeping
 * these here prevents the two sides from silently drifting (e.g. the {@code "SUPER_ADMIN"}
 * role string that {@code JwtAuthFilter} matches on).
 */
public final class JwtClaims {

    /** Claim carrying the principal's role. */
    public static final String ROLE = "role";

    /** Claim carrying the tenant id for tenant-scoped users (absent for SuperAdmin). */
    public static final String TENANT_ID = "tenantId";

    /** Role value used for the platform-level SuperAdmin (distinct from {@code Role.SUPERADMIN}). */
    public static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";

    /**
     * Claim carrying the token's audience/type. Lets the platform console token be told apart
     * from a tenant-user token and (later) from a time-boxed impersonation token. Additive and
     * non-enforced for now — {@link JwtAuthFilter} still routes on {@link #ROLE}; this only lays
     * the groundwork for the impersonation realm in a later phase.
     */
    public static final String TOKEN_TYPE = "typ";

    /** {@link #TOKEN_TYPE} value stamped on a platform SuperAdmin token. */
    public static final String TYPE_PLATFORM = "PLATFORM";

    /** {@link #TOKEN_TYPE} value stamped on a time-boxed impersonation token (acts as a tenant user). */
    public static final String TYPE_IMPERSONATION = "IMPERSONATION";

    /** Impersonation claims — the acting SuperAdmin's id + email carried by an impersonation token. */
    public static final String IMPERSONATOR_ID = "impBy";
    public static final String IMPERSONATOR_EMAIL = "impByEmail";

    /** Token version — bumped on force-reset/lock so live tokens can be invalidated ("reset + kick"). */
    public static final String TOKEN_VERSION = "tv";

    private JwtClaims() {
    }
}