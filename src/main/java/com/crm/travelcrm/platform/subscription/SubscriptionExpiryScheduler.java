package com.crm.travelcrm.platform.subscription;

import com.crm.travelcrm.platform.audit.PlatformAuditRecorder;
import com.crm.travelcrm.platform.audit.entity.PlatformAuditAction;
import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.enums.TenantStatus;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Flips ACTIVE/TRIAL tenants whose {@code subscriptionEndDate} has passed to {@code EXPIRED}
 * (which, via {@code Tenant.isOperational()}, immediately blocks their staff from signing in).
 * Runs daily; also invocable on demand via {@code POST /api/super-admin/subscriptions/run-expiry}.
 *
 * <p>Platform-level: {@link Tenant} carries no tenant @Filter, so no TenantContext is needed. The
 * scheduled run has no acting SuperAdmin, so audit rows are recorded as a system action.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionExpiryScheduler {

    private final TenantRepository tenantRepository;
    private final PlatformAuditRecorder platformAuditRecorder;

    @Scheduled(cron = "${app.subscription.expiry-cron:0 0 3 * * *}")
    public void scheduledRun() {
        int expired = expireOverdue();
        if (expired > 0) {
            log.info("[SubscriptionExpiry] expired {} tenant(s)", expired);
        }
    }

    /**
     * Expires every operational tenant past its end date. Returns how many were expired.
     * Public + {@code @Transactional} so both the cron and the manual trigger share one path.
     */
    @Transactional
    public int expireOverdue() {
        List<Tenant> due = tenantRepository
                .findByStatusInAndSubscriptionEndDateBeforeAndDeletedAtIsNull(
                        List.of(TenantStatus.ACTIVE, TenantStatus.TRIAL), LocalDate.now());

        for (Tenant tenant : due) {
            tenant.setStatus(TenantStatus.EXPIRED);
            tenantRepository.save(tenant);
            platformAuditRecorder.safeRecord(
                    PlatformAuditAction.SUBSCRIPTION_EXPIRED, true,
                    tenant.getId(), tenant.getOrganizationCode(),
                    "TENANT", tenant.getPublicId(),
                    "Subscription expired (end date " + tenant.getSubscriptionEndDate() + ")");
        }
        return due.size();
    }
}