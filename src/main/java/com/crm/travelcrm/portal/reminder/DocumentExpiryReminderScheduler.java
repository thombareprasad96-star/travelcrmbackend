package com.crm.travelcrm.portal.reminder;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * Daily document-expiry reminder job. Runs outside any request, so {@code TenantContext} starts
 * empty and the Hibernate filter wouldn't apply — therefore it iterates tenants and sets the context
 * per tenant (cleared in {@code finally}) before delegating to the {@code @Transactional}
 * {@link DocumentExpiryReminderService}, exactly like {@code TrashPurgeScheduler}. A failure for one
 * tenant is logged and never blocks the others. Idempotent (per-document threshold marker).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentExpiryReminderScheduler {

    private final TenantRepository tenantRepository;
    private final DocumentExpiryReminderService reminderService;

    /** Day-thresholds before expiry, e.g. 60,30,7 (bound from CSV). */
    @Value("${portal.document.expiry-reminder-days}")
    private List<Integer> reminderDays;

    @Scheduled(cron = "${portal.document.expiry-cron}")
    public void run() {
        if (reminderDays == null || reminderDays.isEmpty()) {
            log.warn("Doc-expiry reminders: no thresholds configured — skipping");
            return;
        }
        List<Integer> thresholdsDesc = reminderDays.stream()
                .sorted(Comparator.reverseOrder()).toList();
        LocalDate today = LocalDate.now();
        List<Tenant> tenants = tenantRepository.findAll();
        log.info("Doc-expiry reminder run starting | thresholds={} | tenants={}",
                thresholdsDesc, tenants.size());

        int tenantsOk = 0, totalFired = 0;
        for (Tenant tenant : tenants) {
            try {
                TenantContext.setTenantId(tenant.getId());
                totalFired += reminderService.remindForCurrentTenant(tenant.getId(), thresholdsDesc, today);
                tenantsOk++;
            } catch (Exception e) {
                log.error("Doc-expiry reminders failed for tenant {}: {}", tenant.getId(), e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
        log.info("Doc-expiry reminder run finished | tenantsProcessed={}/{} | remindersFired={}",
                tenantsOk, tenants.size(), totalFired);
    }
}
