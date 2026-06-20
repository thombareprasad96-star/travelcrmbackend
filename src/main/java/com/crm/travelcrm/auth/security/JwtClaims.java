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

    private JwtClaims() {
    }
}