package com.crm.travelcrm.platform.subscription.self;

import com.crm.travelcrm.auth.repository.UserRepository;
import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.platform.billing.dto.BillingRecordResponse;
import com.crm.travelcrm.platform.billing.enums.BillingStatus;
import com.crm.travelcrm.platform.billing.proration.ProrationCalculator;
import com.crm.travelcrm.platform.billing.proration.ProrationResult;
import com.crm.travelcrm.platform.billing.service.BillingService;
import com.crm.travelcrm.platform.subscription.dto.PlanResponse;
import com.crm.travelcrm.platform.subscription.entity.Plan;
import com.crm.travelcrm.platform.subscription.repository.PlanRepository;
import com.crm.travelcrm.platform.subscription.self.dto.MyPlanChangeResponse;
import com.crm.travelcrm.platform.subscription.service.PlanService;
import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.enums.TenantPlan;
import com.crm.travelcrm.tenent.enums.TenantStatus;
import com.crm.travelcrm.tenent.service.TenantService;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Tenant-facing (self-serve) subscription operations, scoped to the caller's own tenant. Reuses the
 * platform primitives: the plan catalogue ({@link PlanService}), billing history ({@link BillingService}),
 * and the proration-aware {@link TenantService#changePlan} — so a self-serve change charges/credits the
 * prorated difference exactly like a SuperAdmin change, then surfaces any top-up invoice to pay.
 */
@Service
@RequiredArgsConstructor
public class MySubscriptionService {

    private final TenantRepository tenantRepository;
    private final PlanRepository planRepository;
    private final PlanService planService;
    private final BillingService billingService;
    private final TenantService tenantService;
    private final UserRepository userRepository;
    private final ProrationCalculator prorationCalculator;

    /** Active plans a tenant may switch to. */
    @Transactional(readOnly = true)
    public List<PlanResponse> listAvailablePlans() {
        return planService.listPlans().stream().filter(PlanResponse::isActive).toList();
    }

    /** The tenant's own invoice history (publicId-keyed; no internal ids). */
    @Transactional(readOnly = true)
    public List<BillingRecordResponse> listInvoices(Long tenantId) {
        Tenant tenant = requireTenant(tenantId);
        return billingService.listForTenant(tenant.getPublicId());
    }

    /**
     * Self-serve upgrade/downgrade.
     *
     * <p><b>Upgrade</b> (target monthly price &gt; current, ACTIVE tenant, same currency): <b>pay-first</b>
     * — issue a prorated upgrade charge tagged with the target plan but do NOT change the plan yet. The
     * plan is applied automatically once the charge is paid (webhook capture or an offline mark-paid).
     * This prevents a tenant from getting a higher tier's entitlements for free.
     *
     * <p><b>Downgrade / lateral / non-ACTIVE / zero-charge</b>: applied immediately (with the seat
     * guardrail on downgrades), delegating to {@link TenantService#changePlan} — a downgrade issues a
     * settled credit note.
     */
    @Transactional
    public MyPlanChangeResponse changePlan(Long tenantId, TenantPlan targetPlan) {
        Tenant tenant = requireTenant(tenantId);
        Plan targetPlanEntity = planRepository.findByCode(targetPlan)
                .orElseThrow(() -> new BusinessException("Unknown plan: " + targetPlan, HttpStatus.BAD_REQUEST));
        if (!targetPlanEntity.isActive()) {
            throw new BusinessException("That plan is not available.", HttpStatus.BAD_REQUEST);
        }
        if (tenant.getPlan() == targetPlan) {
            throw new BusinessException(
                    "You are already on the " + targetPlanEntity.getDisplayName() + " plan.", HttpStatus.BAD_REQUEST);
        }

        Plan currentPlanEntity = planRepository.findByCode(tenant.getPlan()).orElse(null);
        BigDecimal currentPrice = currentPlanEntity != null && currentPlanEntity.getMonthlyPrice() != null
                ? currentPlanEntity.getMonthlyPrice() : BigDecimal.ZERO;
        BigDecimal targetPrice = targetPlanEntity.getMonthlyPrice() != null
                ? targetPlanEntity.getMonthlyPrice() : BigDecimal.ZERO;
        boolean isUpgrade = targetPrice.compareTo(currentPrice) > 0;
        boolean currencyMismatch = currentPlanEntity != null
                && currentPlanEntity.getCurrency() != null && targetPlanEntity.getCurrency() != null
                && !currentPlanEntity.getCurrency().equalsIgnoreCase(targetPlanEntity.getCurrency());

        // ── Pay-first upgrade: ACTIVE tenant, real positive proration, single currency ──
        if (isUpgrade && tenant.getStatus() == TenantStatus.ACTIVE && !currencyMismatch) {
            LocalDate today = LocalDate.now();
            LocalDate periodStart = today.withDayOfMonth(1);
            LocalDate periodEnd = YearMonth.from(today).atEndOfMonth();
            ProrationResult result = prorationCalculator.calculate(
                    currentPrice, targetPrice, today, periodStart, periodEnd);
            if (result.isCharge()) {
                // Supersede any earlier unpaid upgrade order so exactly one is live at a time.
                billingService.voidPendingPlanUpgrades(tenantId);
                String notes = "Upgrade to " + targetPlan + " — pay to activate. Prorated for "
                        + result.remainingDays() + " of " + result.periodDays() + " days ("
                        + periodStart + " to " + periodEnd + ").";
                BillingRecordResponse charge = billingService.issuePlanUpgradeCharge(
                        tenantId, targetPlan, result.amount(), today, periodEnd, notes);
                return MyPlanChangeResponse.builder()
                        .planCode(targetPlan.name())
                        .planDisplayName(targetPlanEntity.getDisplayName())
                        .status(tenant.getStatus().name())
                        .pendingPayment(true)
                        .prorationInvoicePublicId(charge.getPublicId())
                        .amountDue(charge.getAmount())
                        .currency(charge.getCurrency())
                        .message("Pay " + charge.getCurrency() + " " + charge.getAmount() + " to upgrade to "
                                + targetPlanEntity.getDisplayName()
                                + ". Your plan upgrades automatically once payment is confirmed.")
                        .build();
            }
            // No positive charge (e.g. upgrade on the last day of the month) → apply immediately below.
        }

        // ── Immediate change (downgrade / lateral / non-ACTIVE / zero-charge upgrade) ──
        // Downgrade seat guardrail: never strand existing users above the new plan's cap.
        Integer targetSeats = targetPlanEntity.getMaxUsers();
        if (targetSeats != null && targetSeats > 0) {
            long activeUsers = userRepository.countByTenantIdAndDeletedAtIsNull(tenantId);
            if (activeUsers > targetSeats) {
                throw new BusinessException(
                        "The " + targetPlanEntity.getDisplayName() + " plan allows " + targetSeats
                                + " users, but you have " + activeUsers
                                + ". Remove users before switching to this plan.",
                        HttpStatus.CONFLICT);
            }
        }

        // Snapshot the tenant's existing invoices so we can pinpoint exactly what THIS change issued —
        // never mislabel a coincidental same-day invoice (e.g. a SuperAdmin monthly bill) as proration.
        Set<UUID> before = billingService.listForTenant(tenant.getPublicId())
                .stream().map(BillingRecordResponse::getPublicId).collect(Collectors.toSet());

        // Delegates: prorated charge/credit + limit/module re-sync + audit. Same transaction, so the
        // shared Tenant instance reflects the new plan and the issued invoice is visible below.
        tenantService.changePlan(tenant.getPublicId(), targetPlan);

        BillingRecordResponse proration = billingService.listForTenant(tenant.getPublicId())
                .stream().filter(r -> !before.contains(r.getPublicId())).findFirst().orElse(null);
        boolean hasDue = proration != null && proration.getStatus() == BillingStatus.UNPAID;

        String message = hasDue
                ? "Plan changed to " + targetPlanEntity.getDisplayName() + ". A prorated invoice of "
                        + proration.getCurrency() + " " + proration.getAmount() + " is due."
                : "Plan changed to " + targetPlanEntity.getDisplayName() + ".";

        return MyPlanChangeResponse.builder()
                .planCode(targetPlan.name())
                .planDisplayName(targetPlanEntity.getDisplayName())
                .status(tenant.getStatus().name())
                .pendingPayment(false)
                .prorationInvoicePublicId(hasDue ? proration.getPublicId() : null)
                .amountDue(hasDue ? proration.getAmount() : null)
                .currency(hasDue ? proration.getCurrency() : null)
                .message(message)
                .build();
    }

    private Tenant requireTenant(Long tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new BusinessException(
                        "Your organization could not be found.", HttpStatus.FORBIDDEN));
    }
}