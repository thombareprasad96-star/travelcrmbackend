package com.crm.travelcrm.platform.entitlement.filter;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.platform.entitlement.service.TenantEntitlementService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hard enforcement for Feature-Flag module entitlements. When a tenant user hits a module-scoped
 * API path, this rejects the request with 403 unless the tenant's effective modules (own overrides,
 * else plan defaults) include that module — so a disabled module is genuinely inaccessible, not just
 * hidden in the UI.
 *
 * <p>Deliberately conservative:
 * <ul>
 *   <li>Runs only inside the staff chain, after {@code JwtAuthFilter} has populated
 *       {@link TenantContext}. No tenant context (SuperAdmin / portal / pre-auth) ⇒ skip.</li>
 *   <li>Fail-open on any path not in the map below — never blocks something it doesn't recognise.</li>
 *   <li>The shared master dropdowns ({@code /api/masters/dropdown/**}) are consumed by the
 *       Lead/Booking/Quotation forms, so they are never gated.</li>
 * </ul>
 * The effective-module lookup is cached (see {@code TenantEntitlementService}) so this stays cheap
 * per request.
 */
@Component
@RequiredArgsConstructor
public class ModuleAccessFilter extends OncePerRequestFilter {

    private final TenantEntitlementService entitlementService;

    /** Path prefix → required module key. First match wins (LinkedHashMap keeps order). */
    private static final Map<String, String> RULES = new LinkedHashMap<>();
    static {
        RULES.put("/api/leads", "LEADS");
        RULES.put("/api/bookings", "BOOKINGS");
        RULES.put("/api/booking-reminders", "BOOKINGS");
        RULES.put("/api/quotations", "QUOTATIONS");
        RULES.put("/api/customers", "CUSTOMERS");
        RULES.put("/api/vendors", "VENDORS");
        RULES.put("/api/reports", "REPORTS");
        RULES.put("/api/fleet", "FLEET");
        RULES.put("/api/settings/whatsapp", "WHATSAPP");
        RULES.put("/api/hotels", "MASTERS");
        RULES.put("/api/airlines", "MASTERS");
        RULES.put("/api/cruises", "MASTERS");
        RULES.put("/api/vehicles", "MASTERS");
        RULES.put("/api/addons", "MASTERS");
        RULES.put("/api/sightseeings", "MASTERS");
        RULES.put("/api/masters", "MASTERS");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Long tenantId = TenantContext.getTenantId();
        String required = tenantId == null ? null : requiredModule(request.getRequestURI());

        if (required != null && !entitlementService.effectiveModulesFor(tenantId).contains(required)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(
                    "{\"success\":false,\"message\":\"The '" + required
                    + "' module is not enabled for your organization's plan. Contact your administrator.\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /** The module a request path requires, or {@code null} when the path is not module-scoped. */
    private String requiredModule(String path) {
        if (path == null || !path.startsWith("/api/")) return null;
        // Shared master dropdowns feed forms across modules — never gate them.
        if (path.startsWith("/api/masters/dropdown")) return null;
        for (Map.Entry<String, String> e : RULES.entrySet()) {
            String prefix = e.getKey();
            if (path.equals(prefix) || path.startsWith(prefix + "/")) return e.getValue();
        }
        return null;
    }
}
