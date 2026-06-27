package com.crm.travelcrm.portal.security;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.portal.auth.entity.TravelerAccount;
import com.crm.travelcrm.portal.auth.repository.TravelerAccountRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Authenticates travelers on the portal chain ONLY. Guards on three levels so a staff token can
 * never authenticate here:
 * <ol>
 *   <li>It runs only for {@code /api/portal/**} (so even if servlet auto-registration ran it
 *       globally, it is inert on staff paths).</li>
 *   <li>{@link PortalJwtUtil#isTravelerToken} requires the portal signing key + {@code typ/aud}
 *       claims — a staff token fails signature verification outright.</li>
 *   <li>The {@code TravelerAccount} is reloaded and must be active &amp; non-deleted, giving
 *       server-side revocation.</li>
 * </ol>
 *
 * Sets {@code TenantContext} from the token's tenant and <b>always clears it in {@code finally}</b>
 * (thread-pool safety), mirroring the staff filter.
 */
@Component
@RequiredArgsConstructor
public class TravelerJwtAuthFilter extends OncePerRequestFilter {

    private final PortalJwtUtil portalJwtUtil;
    private final TravelerAccountRepository travelerAccountRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        // Belt-and-suspenders: never touch non-portal requests, whatever the registration.
        if (!request.getRequestURI().startsWith("/api/portal/")) {
            chain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }
        String token = authHeader.substring(7);

        if (!portalJwtUtil.isTravelerToken(token)) {
            chain.doFilter(request, response);   // staff/invalid token → stays unauthenticated → 401
            return;
        }

        try {
            if (SecurityContextHolder.getContext().getAuthentication() == null) {
                UUID accountPublicId = portalJwtUtil.extractAccountPublicId(token);
                Long tenantId = portalJwtUtil.extractTenantId(token);
                if (tenantId != null) {
                    TravelerAccount account = travelerAccountRepository
                            .findByPublicIdAndTenantIdAndDeletedAtIsNull(accountPublicId, tenantId)
                            .orElse(null);
                    if (account != null && account.isActive()) {
                        TravelerPrincipal principal = new TravelerPrincipal(
                                account.getId(), account.getPublicId(),
                                account.getCustomerId(), account.getCustomerPublicId(),
                                account.getTenantId(), account.getCustomerName());
                        var auth = new UsernamePasswordAuthenticationToken(
                                principal, null,
                                List.of(new SimpleGrantedAuthority(TravelerPrincipal.AUTHORITY)));
                        SecurityContextHolder.getContext().setAuthentication(auth);
                        TenantContext.setTenantId(tenantId);
                    }
                }
            }
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();   // always — even on exception
        }
    }
}
