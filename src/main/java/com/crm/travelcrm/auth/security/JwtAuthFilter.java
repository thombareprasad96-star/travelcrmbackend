package com.crm.travelcrm.auth.security;

import com.crm.travelcrm.common.context.TenantContext;
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
                    if ("SUPER_ADMIN".equals(role)) {
                        userDetails = superAdminDetailsService.loadUserByUsername(email);
                    } else if (tenantId != null) {
                        userDetails = userDetailsService.loadUserByEmailAndTenantId(email, tenantId);
                    } else {
                        userDetails = userDetailsService.loadUserByUsername(email);
                    }

                    UsernamePasswordAuthenticationToken auth =
                            new UsernamePasswordAuthenticationToken(
                                    userDetails, null, userDetails.getAuthorities());
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);

                    // Set tenant context for this request thread
                    if (tenantId != null) {
                        TenantContext.setTenantId(tenantId);
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
            TenantContext.clear();   // ← CRITICAL — always runs, even on exception
        }
    }
}