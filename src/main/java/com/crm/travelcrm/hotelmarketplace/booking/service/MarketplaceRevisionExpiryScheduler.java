package com.crm.travelcrm.hotelmarketplace.booking.service;

import com.crm.travelcrm.common.context.TenantContext;
import com.crm.travelcrm.hotelmarketplace.booking.entity.PlatformHotelBooking;
import com.crm.travelcrm.hotelmarketplace.booking.repository.PlatformHotelBookingRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Closes revised-price offers the tenant never answered.
 *
 * <p>An offer left open is not merely untidy: the revised amount stays readable, and a readable price
 * is an acceptable one. Accepted weeks later it commits the platform to a rate the supplier withdrew,
 * with the difference coming out of platform earning. The deadline only means something if something
 * enforces it.</p>
 *
 * <p><b>This class finds; it does not transition.</b> The status write is a guarded, row-locked
 * transition that belongs to the platform writer, and a second implementation of it here would be a
 * second thing to keep correct.</p>
 */
@Slf4j
@Component
public class MarketplaceRevisionExpiryScheduler {

    /**
     * The transition this sweep drives. The intended implementor is {@code MarketplacePlatformWriter},
     * which already owns {@code expireRevision(UUID)} — the guarded, pessimistically-locked write.
     */
    public interface RevisionExpiryHandler {
        void expire(UUID bookingPublicId);
    }

    private final PlatformHotelBookingRepository repository;

    /**
     * An {@code ObjectProvider} rather than a plain constructor dependency so the absence of an
     * implementation is a warning on a tick, not a context that refuses to start. The sweep and the
     * writer are landing separately; a scheduler is not worth taking the whole application down for.
     */
    private final ObjectProvider<RevisionExpiryHandler> handlerProvider;

    @Value("${app.marketplace.revision-expiry.batch-size:50}")
    private int batchSize;

    public MarketplaceRevisionExpiryScheduler(PlatformHotelBookingRepository repository,
                                              ObjectProvider<RevisionExpiryHandler> handlerProvider) {
        this.repository = repository;
        this.handlerProvider = handlerProvider;
    }

    /**
     * Every five minutes. Not transactional: the handler owns its own transaction, and holding one
     * open across a whole batch would let a single locked row stall every other expiry.
     */
    @Scheduled(fixedDelayString = "${app.marketplace.revision-expiry.interval-ms:300000}",
               initialDelayString = "${app.marketplace.revision-expiry.initial-delay-ms:150000}")
    public void sweep() {
        List<PlatformHotelBooking> expired = repository.findExpiredRevisions(
                LocalDateTime.now(), PageRequest.of(0, batchSize));
        if (expired.isEmpty()) {
            return;
        }

        RevisionExpiryHandler handler = handlerProvider.getIfAvailable();
        if (handler == null) {
            log.warn("Marketplace revision expiry: {} offer(s) are past their deadline but no "
                    + "RevisionExpiryHandler bean is present — none were expired", expired.size());
            return;
        }

        int closed = 0;
        int failed = 0;
        for (PlatformHotelBooking row : expired) {
            try {
                handler.expire(row.getPublicId());
                closed++;
            } catch (RuntimeException e) {
                // A row somebody is deciding on right now will be locked; it stays in the query and
                // is swept again next tick rather than ending the batch.
                failed++;
                log.error("Revision expiry failed for {}: {}", row.getBookingCode(), e.getMessage());
            } finally {
                // The handler may cross into tenant-scoped work. A pooled scheduler thread carrying a
                // leftover tenant id is a cross-tenant read waiting to happen.
                TenantContext.clear();
            }
        }

        log.info("Marketplace revision expiry: {} offer(s) closed, {} failed", closed, failed);
    }
}
