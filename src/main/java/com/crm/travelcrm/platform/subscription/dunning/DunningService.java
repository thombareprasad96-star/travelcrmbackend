package com.crm.travelcrm.platform.subscription.dunning;

import com.crm.travelcrm.notification.api.NotifyEvent;
import com.crm.travelcrm.notification.domain.enums.DeliveryChannel;
import com.crm.travelcrm.platform.audit.PlatformAuditRecorder;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditAction;
import com.crm.travelcrm.platform.billing.enums.BillingStatus;
import com.crm.travelcrm.platform.billing.repository.BillingRecordRepository;
import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.enums.TenantStatus;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Set;

/**
 * Invoice-driven dunning state machine (grace-then-block). Grace is anchored to the overdue invoice's
 * DUE DATE, not to when the tenant was first flagged, so it is robust to a missed sweep:
 *
 * <ul>
 *   <li><b>ACTIVE → PAST_DUE</b> — the tenant has an unpaid invoice past its due date. Still
 *       operational (see {@link Tenant#isOperational()}); admins are warned.</li>
 *   <li><b>PAST_DUE → EXPIRED</b> — an unpaid invoice is now overdue by more than
 *       {@code app.subscription.grace-days}. Login is blocked.</li>
 *   <li><b>PAST_DUE/EXPIRED → ACTIVE</b> — a webhook capture cleared the last overdue invoice;
 *       access is restored and {@code subscriptionEndDate} extended to the paid period end.</li>
 * </ul>
 *
 * <p>Platform-level: {@link Tenant} has no tenant {@code @Filter}, so no {@code TenantContext} is
 * needed; the notification listener stamps tenancy from each {@link NotifyEvent}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DunningService {

    private final TenantRepository tenantRepository;
    private final BillingRecordRepository billingRepository;
    private final PlatformAuditRecorder platformAuditRecorder;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${app.subscription.grace-days:7}")
    private int graceDays;

    /**
     * The daily sweep. Returns the number of tenant status transitions. Path 1 opens grace for newly
     * overdue tenants; Path 2 expires those whose overdue invoice has outlived the grace window (a
     * tenant that is both newly-overdue AND already past grace transitions ACTIVE→PAST_DUE→EXPIRED in
     * one run — correct, since grace measured from the due date has already elapsed).
     */
    @Transactional
    public int runDunning(LocalDate today) {
        int changes = 0;

        // Path 1: ACTIVE tenant with an overdue unpaid invoice → PAST_DUE.
        for (Long tenantId : billingRepository.findTenantIdsWithStatusDueBefore(BillingStatus.UNPAID, today)) {
            Tenant t = liveTenant(tenantId);
            if (t != null && t.getStatus() == TenantStatus.ACTIVE) {
                t.setStatus(TenantStatus.PAST_DUE);
                if (t.getPastDueSince() == null) {
                    t.setPastDueSince(today);
                }
                tenantRepository.save(t);
                auditPastDue(t, "Entered dunning grace — an invoice is overdue as of " + today);
                notify(t, "PAYMENT_OVERDUE", "Payment overdue",
                        "One or more invoices are overdue. Please pay within " + graceDays
                                + " days to avoid your account being suspended.");
                changes++;
            }
        }

        // Path 2: PAST_DUE tenant whose overdue invoice has passed the grace window → EXPIRED.
        LocalDate graceCutoff = today.minusDays(graceDays);
        for (Long tenantId : billingRepository.findTenantIdsWithStatusDueBefore(BillingStatus.UNPAID, graceCutoff)) {
            Tenant t = liveTenant(tenantId);
            if (t != null && t.getStatus() == TenantStatus.PAST_DUE) {
                t.setStatus(TenantStatus.EXPIRED);
                tenantRepository.save(t);
                platformAuditRecorder.safeRecord(PlatformAuditAction.SUBSCRIPTION_EXPIRED, true,
                        t.getId(), t.getOrganizationCode(), "TENANT", t.getPublicId(),
                        "Dunning grace elapsed with an unpaid invoice — expired on " + today);
                notify(t, "ACCOUNT_SUSPENDED", "Account suspended",
                        "Your account has been suspended for non-payment. Settle the outstanding "
                                + "invoice to restore access.");
                changes++;
            }
        }
        return changes;
    }

    /** Webhook hook: a payment attempt failed → open the grace window if the tenant is currently active. */
    @Transactional
    public void onPaymentFailed(Long tenantId) {
        Tenant t = liveTenant(tenantId);
        if (t == null || t.getStatus() != TenantStatus.ACTIVE) {
            return;
        }
        t.setStatus(TenantStatus.PAST_DUE);
        if (t.getPastDueSince() == null) {
            t.setPastDueSince(LocalDate.now());
        }
        tenantRepository.save(t);
        auditPastDue(t, "Entered dunning grace — a payment attempt failed");
        notify(t, "PAYMENT_FAILED", "Payment failed",
                "Your payment could not be completed. Please retry to keep your account active.");
    }

    /**
     * Webhook / manual hook: an invoice was paid. Two independent effects:
     * <ol>
     *   <li><b>Always</b> advance {@code subscriptionEndDate} to the furthest PAID period end — even for
     *       an ACTIVE tenant — so an on-time payer is renewed and the expiry sweep won't later lock out
     *       a fully-paid account. Uses MAX(periodEnd) over all PAID invoices, so the result is the same
     *       regardless of the order invoices were settled in.</li>
     *   <li><b>Reactivate</b> a tenant that dunning suspended (PAST_DUE, or EXPIRED with the dunning
     *       marker {@code pastDueSince}) once every overdue invoice is cleared. A tenant expired purely
     *       by an end-date lapse (no dunning marker) is only reactivated if the payment actually restored
     *       forward coverage — paying an unrelated add-on can't resurrect a deliberately-lapsed account.</li>
     * </ol>
     * Safe to call on every settlement (webhook capture or SuperAdmin mark-paid); each effect no-ops when
     * it does not apply.
     */
    @Transactional
    public void onInvoicePaid(Long tenantId, LocalDate paidPeriodEnd) {
        Tenant t = liveTenant(tenantId);
        if (t == null) {
            return;
        }
        boolean changed = false;

        // 1. Extend access to the furthest paid period — independent of status and of payment order.
        LocalDate maxPaidEnd = furthest(
                billingRepository.findMaxPeriodEndByTenantIdAndStatusAndDeletedAtIsNull(
                        tenantId, BillingStatus.PAID),
                paidPeriodEnd);
        if (maxPaidEnd != null
                && (t.getSubscriptionEndDate() == null || maxPaidEnd.isAfter(t.getSubscriptionEndDate()))) {
            t.setSubscriptionEndDate(maxPaidEnd);
            changed = true;
        }

        // 2. Reactivate only a dunning-suspended tenant (or one whose payment restored forward coverage)
        //    once no overdue unpaid invoice remains.
        if (t.getStatus() == TenantStatus.PAST_DUE || t.getStatus() == TenantStatus.EXPIRED) {
            boolean stillOverdue = billingRepository
                    .existsByTenantIdAndStatusAndDeletedAtIsNullAndDueDateBefore(
                            tenantId, BillingStatus.UNPAID, LocalDate.now());
            boolean dunningSuspended = t.getPastDueSince() != null;
            boolean hasForwardCoverage = t.getSubscriptionEndDate() != null
                    && !t.getSubscriptionEndDate().isBefore(LocalDate.now());
            if (!stillOverdue && (dunningSuspended || hasForwardCoverage)) {
                t.setStatus(TenantStatus.ACTIVE);
                t.setPastDueSince(null);
                changed = true;
                platformAuditRecorder.safeRecord(PlatformAuditAction.TENANT_REACTIVATE, true,
                        t.getId(), t.getOrganizationCode(), "TENANT", t.getPublicId(),
                        "Reactivated on payment — outstanding invoices cleared"
                                + (t.getSubscriptionEndDate() != null
                                        ? "; access extended to " + t.getSubscriptionEndDate() : ""));
                notify(t, "ACCOUNT_REACTIVATED", "Account reactivated",
                        "Thank you — your payment cleared the outstanding balance and your account is active again.");
            }
        }

        if (changed) {
            tenantRepository.save(t);
        }
    }

    /** The later of two nullable dates (null-tolerant). */
    private static LocalDate furthest(LocalDate a, LocalDate b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }

    private Tenant liveTenant(Long tenantId) {
        Tenant t = tenantRepository.findById(tenantId).orElse(null);
        return (t == null || t.isDeleted()) ? null : t;
    }

    private void auditPastDue(Tenant t, String detail) {
        platformAuditRecorder.safeRecord(PlatformAuditAction.TENANT_PAST_DUE, true,
                t.getId(), t.getOrganizationCode(), "TENANT", t.getPublicId(), detail);
    }

    private void notify(Tenant t, String type, String title, String message) {
        // No explicit recipients → the in-app channel fans out to the tenant's admins/managers.
        eventPublisher.publishEvent(NotifyEvent.builder()
                .type(type)
                .tenantId(t.getId())
                .title(title)
                .message(message)
                .referenceType("TENANT")
                .referencePublicId(t.getPublicId())
                .channels(Set.of(DeliveryChannel.IN_APP))
                .build());
    }
}