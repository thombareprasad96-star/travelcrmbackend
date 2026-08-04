package com.crm.travelcrm.hotelmarketplace.booking.service;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.hotelmarketplace.booking.entity.PlatformHotelBooking;
import com.crm.travelcrm.hotelmarketplace.booking.enums.CrmSyncState;
import com.crm.travelcrm.hotelmarketplace.booking.repository.PlatformHotelBookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Drains bookings that are CONFIRMED on the platform but whose tenant-side projection has not landed.
 *
 * <p>This is the compensating half of the approval: the platform write and the CRM write cannot share
 * a transaction ({@code TenantScope} refuses an active one), so a failure between them leaves a gap
 * that is closed here rather than rolled back — the supplier has already been committed to.</p>
 *
 * <p><b>The attempt cap is the important part.</b> A deterministic failure — a 4xx that this exact
 * input will always produce — would otherwise be replayed on every tick forever, burning work and
 * hiding the problem behind an ever-growing retry count. Past the cap the row becomes
 * {@code ABANDONED}, drops out of the query, and needs a human.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketplaceCrmSyncScheduler {

    private final PlatformHotelBookingRepository repository;
    private final MarketplaceApprovalOrchestrator orchestrator;

    @Value("${app.marketplace.crm-sync.max-attempts:6}")
    private int maxAttempts;

    @Value("${app.marketplace.crm-sync.batch-size:25}")
    private int batchSize;

    /** Every five minutes. Nothing here is time-critical — the platform row is already the truth. */
    @Scheduled(fixedDelayString = "${app.marketplace.crm-sync.interval-ms:300000}",
               initialDelayString = "${app.marketplace.crm-sync.initial-delay-ms:120000}")
    public void drain() {
        List<PlatformHotelBooking> pending = repository.findAwaitingCrmSync(
                List.of(CrmSyncState.PENDING, CrmSyncState.FAILED), PageRequest.of(0, batchSize));
        if (pending.isEmpty()) {
            return;
        }
        log.info("Marketplace CRM sync: {} booking(s) to project", pending.size());

        for (PlatformHotelBooking row : pending) {
            int attempts = row.getCrmSyncAttempts() == null ? 0 : row.getCrmSyncAttempts();
            if (attempts >= maxAttempts) {
                log.error("Marketplace booking {} abandoned after {} CRM projection attempts: {}",
                        row.getBookingCode(), attempts, row.getCrmSyncError());
                orchestrator.projectToCrmAbandon(row);
                continue;
            }
            try {
                // Deliberately the SAME method the approval path uses, not a near-copy — a
                // second implementation of an idempotent upsert is a second thing to keep correct.
                orchestrator.projectToCrm(row);
            } catch (RuntimeException e) {
                // projectToCrm records its own failure; this only stops one bad row from ending the batch.
                log.error("Marketplace CRM sync failed for {}: {}", row.getBookingCode(), e.getMessage());
            } finally {
                // TenantScope restores the previous value, but a scheduler thread is pooled and
                // long-lived: leave nothing behind for whatever runs on it next.
                TenantContext.clear();
            }
        }
    }
}
