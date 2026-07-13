package com.crm.travelcrm.booking.cancellation.service;

import com.crm.travelcrm.tenent.entity.Tenant;
import com.crm.travelcrm.tenent.tenentsRepository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * One-time-per-startup backfill: every existing tenant gets a COMPANY-default cancellation policy if
 * it lacks one, so the feature is safe to enable without a manual migration. Idempotent — the seeder
 * no-ops when a default already exists — and per-tenant failures are isolated so one bad tenant can't
 * abort the rest. Runs after the schema + {@code db/indexes.sql} are in place.
 */
@Component
@Order(100)
@RequiredArgsConstructor
public class CancellationPolicyBackfillRunner implements ApplicationRunner {

    private static final Logger log = LogManager.getLogger(CancellationPolicyBackfillRunner.class);

    private final TenantRepository tenantRepository;
    private final CancellationPolicySeeder seeder;

    @Override
    public void run(ApplicationArguments args) {
        int seeded = 0, failed = 0;
        for (Tenant tenant : tenantRepository.findAll()) {
            if (tenant.getDeletedAt() != null) continue;
            try {
                seeder.ensureCompanyDefault(tenant.getId());   // @Transactional: its own txn per tenant
                seeded++;
            } catch (Exception ex) {
                failed++;
                log.error("Cancellation-policy backfill failed for tenant {}: {}",
                        tenant.getId(), ex.getMessage());
            }
        }
        log.info("Cancellation-policy backfill complete (processed {} tenant(s), {} failed)", seeded, failed);
    }
}