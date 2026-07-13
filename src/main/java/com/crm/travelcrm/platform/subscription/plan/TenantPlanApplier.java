package com.crm.travelcrm.platform.subscription.plan;

import com.crm.travelcrm.platform.audit.PlatformAuditRecorder;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditAction;
import com.crm.travelcrm.platform.subscription.entity.Plan;
import com.crm.travelcrm.platform.subscription.repository.PlanRepository;
import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.enums.TenantPlan;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;

/**
 * Applies a plan's entitlements (enabled modules + numeric limits) to a tenant, with <b>no billing or
 * proration</b> — that is the caller's concern. Deliberately depends only on the tenant + plan
 * repositories and the audit recorder (never {@code BillingService}), so it can be used from both the
 * billing and payment layers without forming a dependency cycle.
 *
 * <p>Used by the pay-first upgrade flow: a paid upgrade invoice applies its {@code upgradeToPlan} here.
 * The plan-entitlement logic mirrors {@code TenantServiceImpl.changePlan} (the SuperAdmin immediate
 * path) — keep the two in sync.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantPlanApplier {

    /** Historical sentinel for an unlimited seat cap (matches TenantServiceImpl). */
    private static final int UNLIMITED_USERS = 1_000_000;

    private final TenantRepository tenantRepository;
    private final PlanRepository planRepository;
    private final PlatformAuditRecorder platformAuditRecorder;

    /**
     * Move the tenant onto {@code targetPlan}, re-syncing its modules and (unless a SuperAdmin quota
     * override is pinned) its numeric limits. Idempotent: a no-op when the tenant is missing/deleted,
     * the plan is unknown, or the tenant is already on that plan.
     */
    @Transactional
    public void applyPlan(Long tenantId, TenantPlan targetPlan, String reason) {
        if (tenantId == null || targetPlan == null) {
            return;
        }
        Tenant t = tenantRepository.findById(tenantId).orElse(null);
        if (t == null || t.isDeleted() || t.getPlan() == targetPlan) {
            return;
        }
        Plan plan = planRepository.findByCode(targetPlan).orElse(null);
        if (plan == null) {
            log.warn("Cannot apply plan {} to tenant {}: plan not found", targetPlan, tenantId);
            return;
        }

        t.setPlan(targetPlan);
        t.setEnabledModules(new HashSet<>(plan.getModules()));
        if (!t.isQuotaOverride()) {
            t.setMaxUsers(plan.getMaxUsers() != null ? plan.getMaxUsers() : UNLIMITED_USERS);
            t.setMaxLeads(plan.getMaxLeads());
            t.setMaxBookingsPerMonth(plan.getMaxBookingsPerMonth());
            t.setMaxStorageMb(plan.getMaxStorageMb());
        }
        tenantRepository.save(t);

        platformAuditRecorder.safeRecord(PlatformAuditAction.PLAN_CHANGE, true,
                t.getId(), t.getOrganizationCode(), "TENANT", t.getPublicId(), reason);
    }
}