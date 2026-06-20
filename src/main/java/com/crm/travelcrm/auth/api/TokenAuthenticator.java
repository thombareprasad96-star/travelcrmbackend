package com.crm.travelcrm.auth.api;

/**
 * Authenticates a raw JWT for flows that cannot use the {@code Authorization} header —
 * notably SSE, where {@code EventSource} can only pass the token as a query parameter.
 *
 * <p>This is the public counterpart to {@code JwtAuthFilter}: it lets other modules
 * establish the security/tenant context from a token without importing {@code JwtUtil},
 * the {@code User} entity, or {@code TenantContext} directly.
 */
public interface TokenAuthenticator {

    /**
     * Validates {@code token} and, on success, populates the SecurityContext and (for
     * tenant users) the TenantContext for the <b>current thread</b>, so downstream calls
     * resolve the authenticated principal. Returns {@code false} if the token is invalid,
     * expired, or its principal can no longer be loaded.
     *
     * <p>Callers in async flows (e.g. SSE) must not rely on a filter to clear TenantContext;
     * follow the module's existing lifecycle handling.
     */
    boolean authenticateForCurrentThread(String token);
}