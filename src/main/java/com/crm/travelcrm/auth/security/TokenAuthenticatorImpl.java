package com.crm.travelcrm.auth.security;

import com.crm.travelcrm.auth.api.TokenAuthenticator;
import com.crm.travelcrm.common.context.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

/**
 * Header-less JWT authentication for SSE and similar flows. Mirrors the routing in
 * {@link JwtAuthFilter} (SuperAdmin vs tenant user vs fallback) so token semantics stay
 * identical across both entry points.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenAuthenticatorImpl implements TokenAuthenticator {

    private final JwtUtil jwtUtil;
    private final SuperAdminDetailsService superAdminDetailsService;
    private final UserDetailsServiceImpl userDetailsService;

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

            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(
                            userDetails, null, userDetails.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(auth);

            if (tenantId != null) {
                TenantContext.setTenantId(tenantId);
            }
            return true;

        } catch (Exception ex) {
            log.warn("Header-less auth failed for email={}: {}", email, ex.getMessage());
            return false;
        }
    }
}