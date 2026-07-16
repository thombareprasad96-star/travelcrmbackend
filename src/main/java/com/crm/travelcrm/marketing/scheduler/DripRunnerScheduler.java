package com.crm.travelcrm.marketing.scheduler;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.marketing.service.DripRunnerService;
import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Runs drip sequences per tenant: syncs segment enrollments then advances due enrollment steps.
 * Same per-tenant {@link TenantContext} discipline as the other Marketing schedulers.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DripRunnerScheduler {

    private final TenantRepository tenantRepository;
    private final DripRunnerService dripRunnerService;

    @Scheduled(cron = "${app.marketing.drip-cron:0 */5 * * * *}")
    public void run() {
        List<Tenant> tenants = tenantRepository.findAll();
        for (Tenant tenant : tenants) {
            try {
                TenantContext.setTenantId(tenant.getId());
                for (Long sequenceId : dripRunnerService.activeSegmentSequenceIds(tenant.getId())) {
                    dripRunnerService.enrollForSequence(sequenceId, tenant.getId());
                }
                dripRunnerService.runDueEnrollments(tenant.getId());
            } catch (Exception e) {
                log.error("Drip run failed for tenant {}: {}", tenant.getId(), e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
    }
}