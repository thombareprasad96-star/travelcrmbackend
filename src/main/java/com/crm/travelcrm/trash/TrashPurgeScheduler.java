package com.crm.travelcrm.trash;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Daily job that hard-purges trashed records older than the configured retention window.
 * This is the <b>only</b> place in the application where a hard delete happens.
 *
 * <p><b>Tenant safety</b> — the job runs outside any request, so {@code TenantContext} and the
 * Hibernate {@code tenantFilter} are empty. Running one tenant-agnostic purge would risk
 * cross-tenant bleed, so instead it iterates tenants and, for each, sets {@code TenantContext}
 * before delegating to the {@code @Transactional} {@link TrashService#purgeForCurrentTenant}
 * (which the filter aspect then scopes), always clearing the context in {@code finally}. A
 * failure for one tenant is logged and never blocks the others. Idempotent and restart-safe.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrashPurgeScheduler {

    private final TenantRepository tenantRepository;
    private final TrashService trashService;
    private final TrashProperties properties;

    @Scheduled(cron = "${app.trash.purge-cron}")
    public void purgeExpiredTrash() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(properties.getRetentionDays());
        List<Tenant> tenants = tenantRepository.findAll();
        log.info("Trash purge starting | retentionDays={} | cutoff={} | tenants={}",
                properties.getRetentionDays(), cutoff, tenants.size());

        int ok = 0;
        for (Tenant tenant : tenants) {
            try {
                TenantContext.setTenantId(tenant.getId());
                trashService.purgeForCurrentTenant(cutoff);
                ok++;
            } catch (Exception e) {
                log.error("Trash purge failed for tenant {}: {}", tenant.getId(), e.getMessage(), e);
            } finally {
                TenantContext.clear();
            }
        }
        log.info("Trash purge finished | tenantsProcessed={}/{}", ok, tenants.size());
    }
}