package com.crm.travelcrm.hotelmarketplace.sync;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.common.context.TenantScope;
import com.crm.travelcrm.master.hotel.Hotel;
import com.crm.travelcrm.master.hotel.HotelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drains tenant Hotel Master projections that a catalog edit left {@code STALE}.
 *
 * <p>Without this, the only thing that re-syncs a projection is a tenant opening that hotel's
 * marketplace detail page. A SuperAdmin correcting an address or deactivating a room would reach the
 * tenant's master — and therefore their quotations and vouchers — only by coincidence, and a hotel
 * nobody browses would quote the wrong address indefinitely. Design doc §6.1 clause 4 names this job;
 * §6.6 is the rule it enforces.</p>
 *
 * <p><b>Not transactional, deliberately.</b> {@link TenantScope} refuses to be entered inside an
 * active transaction — the tenant filter latches {@code @Before} the transactional method, so setting
 * the tenant afterwards is too late and every query in it silently spans all tenants. The scope is
 * therefore entered here, on a plain thread, and each write is a separate cross-bean transactional
 * call into {@link HotelMasterProjectionService#resyncStale}.</p>
 *
 * <p>A permanently failing tenant keeps its rows in the queue and consumes slots on every tick; the
 * summary line flags a full batch so a growing backlog is visible. The common failures do not behave
 * that way — an unresolvable location leaves the row {@code LOCATION_MAPPING_REQUIRED}, which drops
 * out of the query, so the queue self-drains.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketplaceCatalogReconciliationScheduler {

    private final HotelRepository hotelRepository;
    private final HotelMasterProjectionService projectionService;

    @Value("${app.marketplace.catalog-sync.batch-size:100}")
    private int batchSize;

    /** Every fifteen minutes. A projection being a quarter-hour behind is a lag, not an incident. */
    @Scheduled(fixedDelayString = "${app.marketplace.catalog-sync.interval-ms:900000}",
               initialDelayString = "${app.marketplace.catalog-sync.initial-delay-ms:180000}")
    public void reconcile() {
        List<Hotel> stale = hotelRepository.findStaleProjectionsAcrossTenants(PageRequest.of(0, batchSize));
        if (stale.isEmpty()) {
            return;
        }

        // Grouped so the scope is entered once per tenant rather than once per row. Only the scalar
        // identifiers are read off these rows: the query ran outside a transaction, so they are
        // detached and their collections would fail on first touch. resyncStale re-reads its own.
        Map<Long, List<UUID>> byTenant = new LinkedHashMap<>();
        for (Hotel row : stale) {
            byTenant.computeIfAbsent(row.getTenantId(), t -> new ArrayList<>())
                    .add(row.getPlatformHotelPublicId());
        }

        int processed = 0;
        int failedTenants = 0;
        for (Map.Entry<Long, List<UUID>> entry : byTenant.entrySet()) {
            Long tenantId = entry.getKey();
            try {
                TenantScope.run(tenantId, () -> {
                    for (UUID platformHotelPublicId : entry.getValue()) {
                        projectionService.resyncStale(platformHotelPublicId, tenantId);
                    }
                });
                processed += entry.getValue().size();
            } catch (RuntimeException e) {
                // One tenant's broken geography or locked row must not cost every other tenant its
                // tick. The rows stay STALE and are picked up again next time.
                failedTenants++;
                log.error("Catalog reconciliation failed for tenant {}: {}", tenantId, e.getMessage());
            } finally {
                // TenantScope already restores the previous value, but a scheduler thread is pooled
                // and long-lived: leave nothing behind for whatever runs on it next.
                TenantContext.clear();
            }
        }

        log.info("Marketplace catalog reconciliation: {}/{} stale projection(s) re-synced across {} "
                        + "tenant(s), {} tenant(s) failed{}",
                processed, stale.size(), byTenant.size(), failedTenants,
                stale.size() == batchSize ? " — batch full, more remain" : "");
    }
}
