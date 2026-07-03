package com.crm.travelcrm.fleet.scheduler;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.fleet.service.FleetExpiryScanService;
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
 * Daily fleet document-expiry job. Runs outside any request, so it iterates tenants and
 * sets {@code TenantContext} per tenant (cleared in {@code finally}) before delegating to
 * the {@code @Transactional} {@link FleetExpiryScanService} — the exact pattern of the
 * portal's DocumentExpiryReminderScheduler. One tenant's failure never blocks the others.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FleetDocumentExpiryScheduler {

    private final TenantRepository tenantRepository;
    private final FleetExpiryScanService scanService;

    /** Day-thresholds before expiry; 0 fires once more on/after the expiry date. */
    @Value("${app.fleet.expiry-reminder-days:30,15,7,0}")
    private List<Integer> reminderDays;

    @Scheduled(cron = "${app.fleet.expiry-scan-cron:0 30 7 * * *}")
    public void run() {
        if (reminderDays == null || reminderDays.isEmpty()) {
            log.warn("Fleet doc-expiry scan: no thresholds configured — skipping");
            return;
        }
        List<Integer> thresholdsDesc = reminderDays.stream()
                .sorted(Comparator.reverseOrder()).toList();
        LocalDate today = LocalDate.now();
        List<Tenant> tenants = tenantRepository.findAll();
        log.info("Fleet doc-expiry scan starting | thresholds={} | tenants={}",
                thresholdsDesc, tenants.size());

        int tenantsOk = 0, totalFired = 0;
        for (Tenant tenant : tenants) {
            try {
                TenantContext.setTenantId(tenant.getId());
                totalFired += scanService.scanCurrentTenant(tenant.getId(), thresholdsDesc, today);
                tenantsOk++;
            } catch (Exception e) {
                log.error("Fleet doc-expiry scan failed for tenant {}: {}", tenant.getId(), e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
        log.info("Fleet doc-expiry scan finished | tenantsProcessed={}/{} | alertsFired={}",
                tenantsOk, tenants.size(), totalFired);
    }
}