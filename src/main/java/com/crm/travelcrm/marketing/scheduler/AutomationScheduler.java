package com.crm.travelcrm.marketing.scheduler;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.marketing.service.AutomationRunnerService;
import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Fires birthday/anniversary automations per tenant. Runs hourly; each trigger is idempotent
 * per day via {@code lastRunOn}, firing once at/after its configured send time.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AutomationScheduler {

    private final TenantRepository tenantRepository;
    private final AutomationRunnerService automationRunnerService;

    @Scheduled(cron = "${app.marketing.automation-cron:0 0 * * * *}")
    public void run() {
        List<Tenant> tenants = tenantRepository.findAll();
        for (Tenant tenant : tenants) {
            try {
                TenantContext.setTenantId(tenant.getId());
                automationRunnerService.runForTenant(tenant.getId());
            } catch (Exception e) {
                log.error("Automation run failed for tenant {}: {}", tenant.getId(), e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
    }
}