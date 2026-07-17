package com.crm.travelcrm.master.geography.service;

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
 * Backfill: every existing tenant gets the ISO country list on startup.
 *
 * <p>Wiring the seeder into tenant creation only helps tenants created AFTER this ships. The tenants
 * that already exist — including the one on the pilot deployment — would keep their empty Country
 * dropdown forever, so this closes the gap without a manual SQL migration.
 *
 * <p>Idempotent by construction: {@link CountrySeeder} inserts only the codes a tenant is missing, so
 * this is a no-op on every boot after the first. It is also how a country ADDED to the catalogue later
 * reaches existing tenants — a restart is the delivery mechanism.
 *
 * <p>Per-tenant failures are isolated, matching {@code CancellationPolicyBackfillRunner}: one tenant
 * with a hand-made duplicate country must not abort the backfill for everyone else, and must certainly
 * not stop the application from starting.
 *
 * <p>{@code @Order(100)} — after the schema and {@code db/indexes.sql} are in place.
 */
@Component
@Order(100)
@RequiredArgsConstructor
public class CountryBackfillRunner implements ApplicationRunner {

    private static final Logger log = LogManager.getLogger(CountryBackfillRunner.class);

    private final TenantRepository tenantRepository;
    private final CountrySeeder seeder;

    @Override
    public void run(ApplicationArguments args) {
        int tenantsTouched = 0, rowsAdded = 0, failed = 0;

        for (Tenant tenant : tenantRepository.findAll()) {
            if (tenant.getDeletedAt() != null) {
                continue;   // a trashed tenant does not need a country list
            }
            try {
                int added = seeder.ensureCountries(tenant.getId());   // own txn per tenant
                if (added > 0) {
                    tenantsTouched++;
                    rowsAdded += added;
                }
            } catch (Exception ex) {
                failed++;
                log.error("Country backfill failed for tenant {}: {}", tenant.getId(), ex.getMessage());
            }
        }

        if (rowsAdded > 0 || failed > 0) {
            log.info("Country backfill complete — {} row(s) added across {} tenant(s), {} failed.",
                    rowsAdded, tenantsTouched, failed);
        } else {
            log.debug("Country backfill: every tenant already has the full catalogue.");
        }
    }
}
