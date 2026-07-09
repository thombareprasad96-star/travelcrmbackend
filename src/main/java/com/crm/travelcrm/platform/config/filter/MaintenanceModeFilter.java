package com.crm.travelcrm.platform.config.filter;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.platform.config.service.PlatformConfigService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Returns 503 to TENANT traffic while maintenance mode is on. Registered in the staff chain
 * AFTER {@code JwtAuthFilter}, so {@link TenantContext} is already populated: a tenant user has a
 * tenantId; the SuperAdmin (PlatformContext, no tenantId) and pre-auth requests leave it null and
 * pass — that is deliberate, so the console can still toggle maintenance back off.
 *
 * <p>Auto-registration is disabled via a {@code FilterRegistrationBean} in {@code SecurityConfig}
 * (same pattern as {@code RateLimitFilter}) so it runs ONLY inside the staff chain, never globally
 * and never on the portal chain.
 */
@Component
@RequiredArgsConstructor
public class MaintenanceModeFilter extends OncePerRequestFilter {

    private final PlatformConfigService configService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (TenantContext.getTenantId() != null && configService.isMaintenanceEnabled()) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Retry-After", "300");
            response.getWriter().write(
                    "{\"success\":false,\"message\":" + jsonString(configService.maintenanceMessage()) + "}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    /** Minimal JSON string escaping for the maintenance message. */
    private static String jsonString(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}