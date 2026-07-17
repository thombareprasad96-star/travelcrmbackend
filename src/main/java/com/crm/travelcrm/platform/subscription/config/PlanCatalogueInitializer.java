package com.crm.travelcrm.platform.subscription.config;

import com.crm.travelcrm.platform.subscription.entity.Plan;
import com.crm.travelcrm.platform.subscription.repository.PlanRepository;
import com.crm.travelcrm.tenent.enums.TenantPlan;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

/**
 * Seeds the platform plan catalogue on startup, and backfills entitlements added to it later.
 *
 * <p><b>Why this is not part of DevDataSeeder.</b> It used to be, and that made the plan
 * catalogue unreachable in production: DevDataSeeder is
 * {@code @ConditionalOnProperty(app.seed.enabled=true)}, application-prod.properties pins that to a
 * literal {@code false}, and ProductionConfigValidator hard-stops the boot if it is ever true. So on
 * a fresh production database the {@code plans} table stayed empty forever, and there is no API to
 * fill it — PlanController exposes GET and PUT only, no POST.
 *
 * <p>The consequence was not a visible error. {@code TenantServiceImpl} resolves a new tenant's plan
 * with {@code findByCode(...).ifPresent(...)}, so with no plans the tenant simply saved with no
 * modules and null caps; {@code TenantEntitlementService} then returned an empty module set and
 * {@code ModuleAccessFilter} answered 403 MODULE_NOT_ENABLED on /leads, /bookings, /customers,
 * /quotations, /vendors, /reports, /fleet and /masters/**. Auth, /api/me and /api/users kept working,
 * and the frontend's {@code hasModule} is fail-open — so the sidebar rendered complete over an app
 * where every page 403'd. It reads as a broken permission system, not a missing catalogue.
 *
 * <p>Plans are platform data, not demo data: every tenant on every deployment needs them. So this
 * runs unconditionally. It stays safe to run on every boot because both halves are idempotent —
 * seeding is skipped once any plan exists, and the backfill only adds what is missing.
 *
 * <p>Ordered ahead of DevDataSeeder so that in dev the catalogue exists before the demo tenant is
 * created and resolves its plan.
 */
@Slf4j
@Component
@Order(0)
@RequiredArgsConstructor
public class PlanCatalogueInitializer implements ApplicationRunner {

    private final PlanRepository planRepository;

    @Override
    public void run(ApplicationArguments args) {
        seedPlans();
        backfillPlanEntitlements();
    }

    private void seedPlans() {
        if (planRepository.count() > 0) return;
        planRepository.save(Plan.builder()
                .code(TenantPlan.STARTER).displayName("Basic")
                .monthlyPrice(new BigDecimal("2999")).currency("INR")
                .maxUsers(5).maxLeads(500)
                .maxBookingsPerMonth(50).maxStorageMb(512).maxSubAgents(0)
                .modules(new HashSet<>(Set.of("LEADS", "BOOKINGS", "QUOTATIONS", "CUSTOMERS", "MASTERS")))
                .active(true).build());
        planRepository.save(Plan.builder()
                .code(TenantPlan.PRO).displayName("Pro")
                .monthlyPrice(new BigDecimal("7999")).currency("INR")
                .maxUsers(20).maxLeads(5000)
                .maxBookingsPerMonth(500).maxStorageMb(5120).maxSubAgents(5)
                .modules(new HashSet<>(Set.of("LEADS", "BOOKINGS", "QUOTATIONS", "CUSTOMERS", "MASTERS",
                        "VENDORS", "REPORTS", "FLEET", "WHATSAPP", "SUBAGENT")))
                .active(true).build());
        planRepository.save(Plan.builder()
                .code(TenantPlan.ENTERPRISE).displayName("Enterprise")
                .monthlyPrice(new BigDecimal("19999")).currency("INR")
                .maxUsers(null).maxLeads(null)
                .maxBookingsPerMonth(null).maxStorageMb(null).maxSubAgents(50)
                .modules(new HashSet<>(Set.of("LEADS", "BOOKINGS", "QUOTATIONS", "CUSTOMERS", "MASTERS",
                        "VENDORS", "REPORTS", "FLEET", "WHATSAPP", "DISHA_AI", "PORTAL", "SUBAGENT")))
                .active(true).build());
        log.info("[PlanCatalogue] seeded 3 plans (Basic/Pro/Enterprise)");
    }

    /**
     * Backfill for plans seeded before a capability was introduced. {@link #seedPlans()} writes only
     * when the table is empty, so a DB seeded before the SUBAGENT module existed never received it —
     * which hides the module from the SuperAdmin Feature-Flag catalogue ({@code availableModules()} is
     * the union of persisted plan modules) and from the plan editor, and leaves {@code max_sub_agents}
     * NULL. This runs on every startup: it is additive (never removes a module the SuperAdmin has
     * configured) and idempotent (a no-op once the keys are present).
     */
    private void backfillPlanEntitlements() {
        ensureSubAgentEntitlement(TenantPlan.PRO, 5);
        ensureSubAgentEntitlement(TenantPlan.ENTERPRISE, 50);
    }

    /** Ensure {@code plan} grants the SUBAGENT module, and give it a seat cap only if none was ever set. */
    private void ensureSubAgentEntitlement(TenantPlan code, int defaultSeats) {
        planRepository.findByCode(code).ifPresent(plan -> {
            boolean changed = plan.getModules().add("SUBAGENT");   // Set.add == true only when newly added
            // A plan that unlocks the module but caps seats at 0 can't actually create partners. Seed the
            // default only when the SuperAdmin has never set a cap (NULL); a deliberate 0 is left intact.
            if (plan.getMaxSubAgents() == null) {
                plan.setMaxSubAgents(defaultSeats);
                changed = true;
            }
            if (changed) {
                planRepository.save(plan);
                log.info("[PlanCatalogue] backfilled SUBAGENT entitlement on plan {}", code);
            }
        });
    }
}
