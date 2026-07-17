package com.crm.travelcrm.auth.security;

import com.crm.travelcrm.auth.api.TokenAuthenticator;
import com.crm.travelcrm.common.context.PlatformActor;
import com.crm.travelcrm.common.context.PlatformContext;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.entity.SuperAdmin;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

/**
 * Header-less JWT authentication for SSE and similar flows. Mirrors {@link JwtAuthFilter} — both
 * the routing (SuperAdmin vs tenant user vs fallback) and, via the shared
 * {@link JwtPrincipalValidator}, the acceptance checks — so token semantics stay identical across
 * both entry points. Both halves matter: this class once mirrored only the routing, which let a
 * deactivated, locked or force-reset principal hold a live SSE feed until their token expired
 * naturally, while every header-based endpoint correctly rejected them.
 *
 * <p>{@code TenantContext} set here is cleared by {@code ContextCleanupFilter}, which wraps every
 * request — this flow has no {@code Bearer} header, so {@code JwtAuthFilter}'s own cleanup never
 * runs for it.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenAuthenticatorImpl implements TokenAuthenticator {

    private final JwtUtil jwtUtil;
    private final SuperAdminDetailsService superAdminDetailsService;
    private final UserDetailsServiceImpl userDetailsService;
    private final JwtPrincipalValidator principalValidator;

    @Override
    public boolean authenticateForCurrentThread(String token) {
        if (token == null || !jwtUtil.isTokenValid(token)) {
            return false;
        }

        String email    = jwtUtil.extractEmail(token);
        String role     = jwtUtil.extractRole(token);
        Long   tenantId = jwtUtil.extractTenantId(token);

        try {
            UserDetails userDetails;
            if (JwtClaims.ROLE_SUPER_ADMIN.equals(role)) {
                userDetails = superAdminDetailsService.loadUserByUsername(email);
            } else if (tenantId != null) {
                userDetails = userDetailsService.loadUserByEmailAndTenantId(email, tenantId);
            } else {
                userDetails = userDetailsService.loadUserByUsername(email);
            }

            if (!principalValidator.isAcceptable(userDetails, token)) {
                log.warn("Header-less auth rejected — principal disabled/locked/stale: {}", email);
                return false;
            }

            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(
                            userDetails, null, userDetails.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(auth);

            // Mirror JwtAuthFilter's context setup, not just its routing. Without the SuperAdmin
            // branch a header-less platform flow (the console SSE stream) authenticates but never
            // enters PlatformContext, so anything downstream that asks "is this the platform actor?"
            // — the audit trail, any cross-tenant assertion — silently answers no.
            if (tenantId != null) {
                TenantContext.setTenantId(tenantId);
            } else if (userDetails instanceof SuperAdmin superAdmin) {
                PlatformContext.enter(new PlatformActor(superAdmin.getId(), superAdmin.getEmail()));
            }
            return true;

        } catch (Exception ex) {
            log.warn("Header-less auth failed for email={}: {}", email, ex.getMessage());
            return false;
        }
    }
}