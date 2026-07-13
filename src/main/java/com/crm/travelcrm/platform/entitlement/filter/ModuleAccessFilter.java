package com.crm.travelcrm.platform.entitlement.filter;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.exception.ApiErrorWriter;
import com.crm.travelcrm.common.exception.ErrorCode;
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
    private final ApiErrorWriter errorWriter;

    /** Path prefix → required module key. First match wins (LinkedHashMap keeps order). */
    private static final Map<String, String> RULES = new LinkedHashMap<>();
    static {
        RULES.put("/api/leads", "LEADS");
        RULES.put("/api/bookings", "BOOKINGS");
        RULES.put("/api/booking-reminders", "BOOKINGS");
        RULES.put("/api/quotations", "QUOTATIONS");
        // Templates exist only to seed quotations, so they ride the QUOTATIONS entitlement. The
        // prefix match is exact-or-followed-by-slash, so this needs its own rule: "/api/quotations"
        // does not cover "/api/quotation-templates". Without it the endpoint would be reachable by a
        // tenant whose plan excludes quotations entirely.
        RULES.put("/api/quotation-templates", "QUOTATIONS");
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
        RULES.put("/api/testimonials", "MASTERS");
        RULES.put("/api/masters", "MASTERS");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Long tenantId = TenantContext.getTenantId();
        String required = tenantId == null ? null : requiredModule(request.getRequestURI());

        if (required != null && !entitlementService.effectiveModulesFor(tenantId).contains(required)) {
            // MODULE_NOT_ENABLED, not PERMISSION_DENIED: same 403, different screen. The user's
            // permissions are fine — their organization's plan is what excludes this module, so the
            // client shows an upgrade prompt rather than "ask your administrator for access".
            errorWriter.write(response, ErrorCode.MODULE_NOT_ENABLED,
                    "The '" + required + "' module is not enabled for your organization's plan. "
                            + "Contact your administrator.");
            return;
        }
        chain.doFilter(request, response);
    }

    /** The module a request path requires, or {@code null} when the path is not module-scoped. */
    private String requiredModule(String path) {
        if (path == null || !path.startsWith("/api/")) return null;
        // Shared master dropdowns feed forms across modules — never gate them.
        if (path.startsWith("/api/masters/dropdown")) return null;
        // A lead→booking conversion CREATES a booking, so it must ride the BOOKINGS entitlement
        // even though it is hosted under /api/leads. The discriminating segment sits AFTER the
        // variable {publicId}, so it cannot be expressed as a prefix rule in RULES — special-case
        // it here, before the generic /api/leads rule below would otherwise claim it for LEADS.
        if (path.startsWith("/api/leads/") && path.endsWith("/convert-to-booking")) return "BOOKINGS";
        for (Map.Entry<String, String> e : RULES.entrySet()) {
            String prefix = e.getKey();
            if (path.equals(prefix) || path.startsWith(prefix + "/")) return e.getValue();
        }
        return null;
    }
}
