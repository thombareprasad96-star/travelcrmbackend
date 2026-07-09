package com.crm.travelcrm.auth.security;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.common.context.PlatformActor;
import com.crm.travelcrm.common.context.PlatformContext;
import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.entity.SuperAdmin;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final SuperAdminDetailsService superAdminDetailsService;
    private final UserDetailsServiceImpl userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        // The traveler portal has its own realm + filter chain. Never authenticate a portal request
        // through the staff context — this guarantees a staff token cannot act on /api/portal/**.
        if (request.getRequestURI().startsWith("/api/portal/")) {
            chain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);

        if (!jwtUtil.isTokenValid(token)) {
            chain.doFilter(request, response);
            return;
        }

        String email    = jwtUtil.extractEmail(token);
        String role     = jwtUtil.extractRole(token);
        Long   tenantId = jwtUtil.extractTenantId(token);

        try {
            if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                try {
                    UserDetails userDetails;
                    if (JwtClaims.ROLE_SUPER_ADMIN.equals(role)) {
                        userDetails = superAdminDetailsService.loadUserByUsername(email);
                    } else if (tenantId != null) {
                        userDetails = userDetailsService.loadUserByEmailAndTenantId(email, tenantId);
                    } else {
                        userDetails = userDetailsService.loadUserByUsername(email);
                    }

                    // Reject deactivated principals (User.isEnabled() == isActive;
                    // SuperAdmin.isEnabled() == enabled). Soft-deleted users are already
                    // excluded by the loaders, so they surface as "not found" above.
                    if (!userDetails.isEnabled() || !userDetails.isAccountNonLocked()
                            || isStaleToken(userDetails, token)) {
                        logger.warn("JWT principal is disabled/locked/stale: " + email);
                        SecurityContextHolder.clearContext();
                    } else {
                        UsernamePasswordAuthenticationToken auth =
                                new UsernamePasswordAuthenticationToken(
                                        userDetails, null, userDetails.getAuthorities());
                        auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                        SecurityContextHolder.getContext().setAuthentication(auth);

                        // Set the per-request context. Tenant users get a TenantContext; the
                        // platform SuperAdmin gets an explicit PlatformContext — a NAMED
                        // cross-tenant marker (see PlatformContext) so god-mode reads are
                        // intentional and auditable, never inferred from "tenantId is null".
                        if (tenantId != null) {
                            TenantContext.setTenantId(tenantId);
                        } else if (userDetails instanceof SuperAdmin superAdmin) {
                            PlatformContext.enter(
                                    new PlatformActor(superAdmin.getId(), superAdmin.getEmail()));
                        }
                    }
                } catch (Exception ex) {
                    // Token is valid but the principal no longer exists (deleted /
                    // deactivated user). Continue unauthenticated so protected
                    // endpoints return 401 instead of a 500 from inside the filter.
                    logger.warn("JWT principal could not be loaded: " + ex.getMessage());
                    SecurityContextHolder.clearContext();
                }
            }

            chain.doFilter(request, response);

        } finally {
            TenantContext.clear();     // ← CRITICAL — always runs, even on exception
            PlatformContext.clear();   // platform (god-mode) marker — same lifecycle
        }
    }

    /**
     * "Reset + kick": a tenant user's live tokens are invalidated when a SuperAdmin force-reset or
     * lock bumped {@code User.tokenVersion}. Old tokens carry a lower (or absent → 0) 'tv' claim, so
     * they stop matching and are rejected here on the very next request. SuperAdmin tokens are not
     * versioned, so they are never considered stale.
     */
    private boolean isStaleToken(UserDetails userDetails, String token) {
        if (userDetails instanceof User user) {
            Integer claimTv = jwtUtil.extractTokenVersion(token);
            int tokenTv = claimTv != null ? claimTv : 0;
            return tokenTv != user.getTokenVersionOrZero();
        }
        return false;
    }
}