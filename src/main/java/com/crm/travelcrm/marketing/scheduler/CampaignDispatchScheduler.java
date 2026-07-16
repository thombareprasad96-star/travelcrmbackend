package com.crm.travelcrm.marketing.scheduler;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.marketing.service.CampaignDispatchService;
import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Dispatches due campaigns per tenant. Sets {@link TenantContext} for each tenant (so new recipient
 * rows + WhatsApp logs are stamped correctly), clears it in {@code finally}, and never lets one
 * tenant's failure block the others. The actual work is delegated cross-bean to
 * {@link CampaignDispatchService} so each campaign runs in its own transaction (no self-invocation).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CampaignDispatchScheduler {

    private final TenantRepository tenantRepository;
    private final CampaignDispatchService dispatchService;

    @Scheduled(cron = "${app.marketing.campaign-cron:0 * * * * *}")
    public void run() {
        List<Tenant> tenants = tenantRepository.findAll();
        for (Tenant tenant : tenants) {
            try {
                TenantContext.setTenantId(tenant.getId());
                for (Long campaignId : dispatchService.dueCampaignIds(tenant.getId())) {
                    dispatchService.dispatchCampaign(campaignId, tenant.getId());
                }
            } catch (Exception e) {
                log.error("Campaign dispatch failed for tenant {}: {}", tenant.getId(), e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
    }
}