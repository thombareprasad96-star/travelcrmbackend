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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

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
        // B2B sub-agents (Travel Partner). One rule covers both the management API and the tenant's
        // seat-license purchases at "/api/subagents/license-requests" (prefix-or-slash match), so a
        // tenant whose plan excludes the module can neither create partners nor buy seats for them.
        // NOT gated here: "/api/me/commissions" (a sub-agent reading their OWN ledger — a shared /api/me
        // namespace) and the SuperAdmin queue at "/api/super-admin/subagent-license-requests" (no
        // TenantContext, so this filter skips it anyway).
        RULES.put("/api/subagents", "SUBAGENT");
        RULES.put("/api/settings/whatsapp", "WHATSAPP");
        // ── Subsystems that shipped with NO entitlement rule and were reachable on every plan ──
        // Accounting, Marketing, Tasks/Calendar, Reminders and the CRM dashboard were all built after
        // the original rule set and none was ever added here, so fail-open left them open to any
        // tenant regardless of plan. That is also what would let a Fleet-only tenant browse a CRM it
        // never bought. PlanCatalogueInitializer.backfillUngatedModuleKeys() grants these keys to the
        // existing plans on startup, and MUST have run before these rules take effect — otherwise
        // every current tenant is 403'd out of screens they use daily.
        RULES.put("/api/accounting", "ACCOUNTING");
        RULES.put("/api/tax-rates", "ACCOUNTING");
        RULES.put("/api/marketing", "MARKETING");
        RULES.put("/api/tasks", "TASKS");
        RULES.put("/api/calendar", "TASKS");          // the calendar is an aggregation over tasks
        RULES.put("/api/reminders", "REMINDERS");
        RULES.put("/api/dashboard", "DASHBOARD");
        RULES.put("/api/lead-sources", "LEADS");      // inbound capture feeds the lead pipeline
        RULES.put("/api/cancellation-policies", "BOOKINGS");
        // Optional paid add-on, NOT part of MASTERS. The tenant's own private hotel master at
        // "/api/hotels" must keep working on every plan even when the marketplace is off (design doc
        // §20.1), so the two prefixes carry different keys on purpose. PlanCatalogueInitializer
        // registers HOTEL_MARKETPLACE but grants it to ENTERPRISE only — nothing is given away here.
        RULES.put("/api/hotel-marketplace", "HOTEL_MARKETPLACE");
        RULES.put("/api/hotels", "MASTERS");
        RULES.put("/api/airlines", "MASTERS");
        RULES.put("/api/cruises", "MASTERS");
        RULES.put("/api/vehicles", "MASTERS");
        RULES.put("/api/addons", "MASTERS");
        RULES.put("/api/sightseeings", "MASTERS");
        RULES.put("/api/testimonials", "MASTERS");
        RULES.put("/api/masters", "MASTERS");
        // Served by DestinationController, which is mapped to a bare "/api" — easy to miss, and
        // master geography must ride the same entitlement as the rest of the master data.
        RULES.put("/api/destinations", "MASTERS");
        RULES.put("/api/countries", "MASTERS");
        RULES.put("/api/cities", "MASTERS");
        RULES.put("/api/v1", "MASTERS");              // nested geography family
    }

    /**
     * Paths that are deliberately NOT module-scoped, and why. This is the other half of the rule set:
     * every tenant-facing {@code /api} prefix in the tree must appear here or in {@link #RULES}, and
     * {@code ModuleAccessCoverageTest} fails the build when a new controller appears in neither.
     *
     * <p>That test is the real control. The filter itself stays fail-OPEN at runtime — flipping it to
     * fail-closed on a live single-tenant deployment risks 403-ing a path nobody enumerated, and the
     * build-time check gives the same guarantee for code we own without that risk. Once the coverage
     * test has been green across a few releases, {@code app.entitlement.fail-closed=true} makes the
     * runtime strict too; the wiring is already here.
     *
     * <p>These are PLATFORM capabilities — a Fleet-only tenant needs every one of them. Gating any of
     * them on a CRM module key would break the standalone product.
     */
    private static final Set<String> ALWAYS_ALLOWED = Set.of(
            "/api/auth",                   // pre-auth; no tenant bound anyway
            "/api/me",                     // identity, entitlements, onboarding — how a client learns its own plan
            // Already covered by "/api/me" above, and listed anyway ON PURPOSE. Confirmed marketplace
            // hotel bookings and issued vouchers must survive a lapsed HOTEL_MARKETPLACE add-on
            // (design §20.5, acceptance criterion §17): a tenant whose renewal fails must not lose the
            // voucher for a guest's stay next week. The obvious "tidy-up" is to add
            // /api/me/hotel-bookings to RULES under HOTEL_MARKETPLACE — RULES is consulted first, so
            // that would silently re-break it. Naming the prefix here makes that edit collide in
            // ModuleAccessCoverageTest.rulesAndAllowlistDoNotOverlap() instead of in production.
            "/api/me/hotel-bookings",
            "/api/users",                  // user management is a platform capability
            "/api/permissions",
            "/api/permission-templates",
            "/api/company",                // profile, branding, billing, subscription — must stay reachable
            "/api/settings/email",         // tenant mail config
            "/api/notifications",
            "/api/notification-settings",
            "/api/trash",                  // restoring a fleet vehicle must not need a CRM module
            // Ending an impersonation session. Emphatically NOT module-gated: a SuperAdmin
            // impersonating a tenant whose plan excludes some module must still be able to get out,
            // and gating the exit is how someone ends up stuck inside another tenant's session.
            "/api/impersonation",
            "/api/masters/dropdown",       // shared form dropdowns, consumed across every module
            "/api/webhooks",               // public ingest; no tenant context
            "/api/public",                 // public quotation web links
            "/api/portal",                 // traveler realm — separate security chain entirely
            "/api/portal-admin",
            "/api/super-admin"             // platform realm; no TenantContext, filter skips it
    );

    /**
     * Whether an unclassified {@code /api} path is denied at runtime. Default false — see the note on
     * {@link #ALWAYS_ALLOWED}. Not yet read by {@link #requiredModule}; wiring it is a one-line change
     * once the coverage test has proven the allowlist complete.
     */
    @Value("${app.entitlement.fail-closed:false}")
    private boolean failClosed;

    /** Exposed for the coverage test, which must check the same data the filter uses. */
    public static Set<String> rulePrefixes() {
        return Set.copyOf(RULES.keySet());
    }

    public static Set<String> allowedPrefixes() {
        return ALWAYS_ALLOWED;
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
