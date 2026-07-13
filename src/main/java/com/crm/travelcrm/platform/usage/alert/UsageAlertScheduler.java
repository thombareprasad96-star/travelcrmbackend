package com.crm.travelcrm.platform.usage.alert;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Daily usage-alert sweep. Runs outside any request, so {@code TenantContext} starts empty and the
 * Hibernate tenant filter wouldn't apply — therefore it iterates live tenants and sets the context
 * per tenant (cleared in {@code finally}) before delegating to the {@code @Transactional}
 * {@link UsageAlertService}, exactly like {@code DocumentExpiryReminderScheduler}. A failure for one
 * tenant is logged and never blocks the others. Idempotent per (tenant, metric, month, level).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UsageAlertScheduler {

    private final TenantRepository tenantRepository;
    private final UsageAlertService usageAlertService;

    @Scheduled(cron = "${app.usage.scan-cron:0 0 6 * * *}")
    public void run() {
        LocalDate today = LocalDate.now();
        List<Tenant> tenants = tenantRepository.findByDeletedAtIsNull();
        log.info("Usage-alert run starting | tenants={}", tenants.size());

        int tenantsOk = 0, totalFired = 0;
        for (Tenant tenant : tenants) {
            try {
                TenantContext.setTenantId(tenant.getId());
                totalFired += usageAlertService.scanCurrentTenant(tenant.getId(), today);
                tenantsOk++;
            } catch (Exception e) {
                log.error("Usage-alert scan failed for tenant {}: {}", tenant.getId(), e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
        log.info("Usage-alert run finished | tenantsProcessed={}/{} | alertsFired={}",
                tenantsOk, tenants.size(), totalFired);
    }
}