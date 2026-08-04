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
import java.util.List;
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

    /**
     * The lead cap Basic shipped with, and the cap it should have had. Basic's lead limit is a
     * LIFETIME total, not a monthly allowance — {@code LeadServiceImpl.enforceLeadCap} compares
     * against {@code countByTenantIdAndDeletedAtIsNull}, i.e. every lead the tenant has ever kept.
     * At 500 an agency doing Basic's own 50 bookings a month walls itself shut in three or four
     * months and cannot create another lead, however long it keeps paying. That is churn, not an
     * upsell. 5000 keeps the plan usable for years; Pro still differentiates on users, bookings,
     * storage and the Reports/Accounting/Marketing modules.
     */
    private static final int LEGACY_STARTER_LEAD_CAP = 500;
    private static final int STARTER_LEAD_CAP = 5000;

    private final PlanRepository planRepository;

    @Override
    public void run(ApplicationArguments args) {
        seedPlans();
        ensureFleetPlan();
        backfillPlanEntitlements();
    }

    /**
     * The Fleet-only plan: Vehicle Diary sold as its own product.
     *
     * <p>Has to be its own idempotent step rather than a line in {@link #seedPlans()}, because that
     * method returns early the moment ANY plan exists — so on every database seeded before this
     * plan was invented (which is all of them, including the pilot) it would never appear, and
     * there is no API to create one.
     *
     * <p><b>modules = {FLEET} and nothing else.</b> That single entry is the entire product
     * boundary: {@code ModuleAccessFilter} answers 403 MODULE_NOT_ENABLED on every CRM prefix for
     * a tenant on this plan, while its {@code ALWAYS_ALLOWED} set keeps the platform capabilities a
     * fleet operator still needs — login, users, company profile, notifications, trash.
     *
     * <p>Requires V2 PART 12, which widens the seven CHECK constraints that name the plan enum.
     * Without it this insert fails on {@code plans_code_check}.
     */
    private void ensureFleetPlan() {
        if (planRepository.findByCode(TenantPlan.FLEET).isPresent()) return;
        planRepository.save(Plan.builder()
                .code(TenantPlan.FLEET).displayName("Vehicle Diary")
                .monthlyPrice(new BigDecimal("1999")).currency("INR")
                // A transport operator's seats: dispatchers, an accountant, the owner. Leads and
                // bookings caps stay null — the modules are off, so a number there would only
                // invite someone to read meaning into it.
                .maxUsers(10).maxLeads(null)
                .maxBookingsPerMonth(null)
                // Receipt photos and certificate scans are the storage here: ~50 receipts a month
                // at half a megabyte is 300 MB a year for a ten-vehicle fleet.
                .maxStorageMb(2048).maxSubAgents(0)
                .modules(new HashSet<>(Set.of("FLEET")))
                .active(true).build());
        log.info("[PlanCatalogue] created the FLEET plan (Vehicle Diary — modules = {FLEET})");
    }

    private void seedPlans() {
        if (planRepository.count() > 0) return;
        // VENDORS and WHATSAPP are on Basic on purpose — see backfillStarterEntitlements(). Keep the
        // two module lists identical or a fresh database and an upgraded one sell different products.
        planRepository.save(Plan.builder()
                .code(TenantPlan.STARTER).displayName("Basic")
                .monthlyPrice(new BigDecimal("2999")).currency("INR")
                .maxUsers(5).maxLeads(STARTER_LEAD_CAP)
                .maxBookingsPerMonth(50).maxStorageMb(512).maxSubAgents(0)
                .modules(new HashSet<>(Set.of("LEADS", "BOOKINGS", "QUOTATIONS", "CUSTOMERS", "MASTERS",
                        "VENDORS", "WHATSAPP")))
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
        backfillUngatedModuleKeys();
        backfillStarterEntitlements();
    }

    /**
     * Repairs two things Basic shipped wrong. Neither is generosity — both are defects in the split.
     *
     * <p><b>VENDORS.</b> Basic grants BOOKINGS, and the booking module is built ON vendors:
     * {@code BookingExpense.vendorId}, {@code BookingServiceItem}, {@code AssignVendorRequest} and
     * {@code BookingProfitService} all key off one. With {@code /api/vendors} 403'd and no vendor
     * entry in the always-allowed {@code /api/masters/dropdown} family, a Basic tenant sees the
     * expense ledger and the service-item vendor assignment but cannot populate either — the feature
     * is present and non-functional, which reads as a broken product rather than a locked one.
     *
     * <p><b>WHATSAPP.</b> {@link #backfillCommunicationKey()} put the omnichannel inbox on every
     * plan, but {@code ModuleAccessFilter} gates {@code /api/settings/whatsapp} on WHATSAPP, which
     * was Pro-only. So Basic received a WhatsApp inbox it had no way to connect. The two keys have
     * to sit on the same side of the paywall; putting them both on Basic is the side that keeps the
     * inbox working.
     *
     * <p>The Pro upsell is unchanged and intact: REPORTS, ACCOUNTING, MARKETING, FLEET, SUBAGENT
     * (and PORTAL / DISHA_AI / HOTEL_MARKETPLACE above it) all stay out of Basic.
     *
     * <p>As everywhere else in this class, the plan-level grant does NOT reach an existing tenant —
     * {@code TenantEntitlementService.computeEffectiveModules} returns the tenant's own
     * {@code tenant_modules} snapshot whenever it is non-empty and never consults the plan. V2 PART
     * 19 carries the matching tenant-level backfill for both the modules and the lead cap.
     */
    private void backfillStarterEntitlements() {
        ensureModules(TenantPlan.STARTER, "VENDORS", "WHATSAPP");
        raiseStarterLeadCap();
    }

    /**
     * Raise Basic's lifetime lead cap {@value #LEGACY_STARTER_LEAD_CAP} → {@value #STARTER_LEAD_CAP}.
     *
     * <p>Fires <b>only</b> when the stored cap is still exactly the old seeded default. A SuperAdmin
     * who has deliberately tuned this plan (to 1000, or down to 200 for a trial cohort) keeps their
     * number — same principle as the {@code null}-guard in {@link #ensureSubAgentEntitlement}, and
     * the reason this cannot simply be an unconditional {@code setMaxLeads}. Naturally idempotent:
     * after the first run the value is no longer 500, so the guard never passes again.
     */
    private void raiseStarterLeadCap() {
        planRepository.findByCode(TenantPlan.STARTER).ifPresent(plan -> {
            if (!Integer.valueOf(LEGACY_STARTER_LEAD_CAP).equals(plan.getMaxLeads())) return;
            plan.setMaxLeads(STARTER_LEAD_CAP);
            planRepository.save(plan);
            log.info("[PlanCatalogue] raised the Basic lead cap {} -> {}",
                    LEGACY_STARTER_LEAD_CAP, STARTER_LEAD_CAP);
        });
    }

    /**
     * Grants the module keys for subsystems that shipped WITHOUT an entitlement rule.
     *
     * <p>Accounting, Marketing, Tasks/Calendar, Reminders and the CRM Dashboard were all built after
     * the plan catalogue was seeded and none of them was ever added to a plan. They stayed reachable
     * only because {@code ModuleAccessFilter} is fail-open on unmapped prefixes — so today any tenant
     * on any plan can call {@code /api/accounting/**} and {@code /api/marketing/**} regardless of what
     * they bought.
     *
     * <p><b>This backfill must land BEFORE the matching rules are added to that filter</b>, or the
     * first deploy carrying the rules would 403 every existing tenant out of screens they use daily —
     * the same failure mode as an empty {@code plans} table. It runs on every startup, is additive,
     * and never removes a key a SuperAdmin has configured.
     *
     * <p>TASKS, REMINDERS and DASHBOARD go to every plan including Basic: they are core CRM
     * navigation, not premium capabilities, and a tenant that loses them loses the product. ACCOUNTING
     * and MARKETING are Pro-and-above, matching how they are actually sold.
     */
    private void backfillUngatedModuleKeys() {
        ensureModules(TenantPlan.STARTER, "TASKS", "REMINDERS", "DASHBOARD");
        ensureModules(TenantPlan.PRO, "TASKS", "REMINDERS", "DASHBOARD", "ACCOUNTING", "MARKETING");
        ensureModules(TenantPlan.ENTERPRISE, "TASKS", "REMINDERS", "DASHBOARD", "ACCOUNTING", "MARKETING");
        backfillHotelMarketplaceKey();
        backfillCommunicationKey();
    }

    /**
     * Grants {@code COMMUNICATION} — the omnichannel Communication Center — to every plan.
     *
     * <p>On every plan including Starter, deliberately: the module is the inbox for WhatsApp, email
     * and calls the agency already uses, not a premium add-on, and a tenant that loses it loses the
     * channel its customers actually contact it on. Same reasoning as TASKS/REMINDERS/DASHBOARD
     * above, and the opposite of {@link #backfillHotelMarketplaceKey()}.
     *
     * <p><b>This is only half the story.</b> Granting a key to the PLANS does not reach an existing
     * TENANT: {@code TenantEntitlementService.computeEffectiveModules} returns the tenant's own
     * {@code tenant_modules} snapshot whenever it is non-empty and never falls back to the plan, and
     * {@code TenantServiceImpl} snapshots modules at tenant creation. V2 PART 17 carries the
     * matching {@code INSERT INTO tenant_modules} backfill. Without it, every existing tenant is
     * 403 MODULE_NOT_ENABLED the moment the ModuleAccessFilter rule lands.
     */
    private void backfillCommunicationKey() {
        ensureModules(TenantPlan.STARTER, "COMMUNICATION");
        ensureModules(TenantPlan.PRO, "COMMUNICATION");
        ensureModules(TenantPlan.ENTERPRISE, "COMMUNICATION");
    }

    /**
     * Registers the {@code HOTEL_MARKETPLACE} module key so the SuperAdmin can grant it.
     *
     * <p>Unlike the backfill above, this one grants the key to NO plan. The marketplace is sold as an
     * optional paid add-on (design doc §20.3), so switching it on for every existing tenant would be
     * giving away the product. The key still has to EXIST, because the Feature-Flag catalogue and the
     * plan editor both derive their module list from the union of persisted plan modules — a key no
     * plan has ever held is invisible in the console and cannot be granted through the UI at all.</p>
     *
     * <p>ENTERPRISE carries it as the sellable default; STARTER and PRO get it per-tenant, either by
     * a SuperAdmin grant or when the add-on subscription lands.</p>
     */
    private void backfillHotelMarketplaceKey() {
        ensureModules(TenantPlan.ENTERPRISE, "HOTEL_MARKETPLACE");
    }

    /** Additively grant {@code keys} to {@code code}; a no-op once they are present. */
    private void ensureModules(TenantPlan code, String... keys) {
        planRepository.findByCode(code).ifPresent(plan -> {
            boolean changed = false;
            for (String key : keys) {
                changed |= plan.getModules().add(key);   // Set.add == true only when newly added
            }
            if (changed) {
                planRepository.save(plan);
                log.info("[PlanCatalogue] backfilled module keys {} on plan {}", List.of(keys), code);
            }
        });
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
