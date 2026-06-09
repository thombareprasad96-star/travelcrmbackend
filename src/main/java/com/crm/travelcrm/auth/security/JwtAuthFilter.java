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
        Long   tenantId = jwtUtil.extractTenantId(token);   // ← NEW
        System.out.println(">>> JWT tenantId extracted: " + tenantId + " for " + email); // ← ADD

        try {
            if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {

                UserDetails userDetails = "SUPER_ADMIN".equals(role)
                        ? superAdminDetailsService.loadUserByUsername(email)
                        : userDetailsService.loadUserByUsername(email);

                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(
                                userDetails, null, userDetails.getAuthorities());
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);

                // Set tenant context for this request thread
                if (tenantId != null) {
                    TenantContext.setTenantId(tenantId);     // ← NEW
                }
            }

            chain.doFilter(request, response);

        } finally {
            TenantContext.clear();   // ← CRITICAL — always runs, even on exception
        }
    }
}