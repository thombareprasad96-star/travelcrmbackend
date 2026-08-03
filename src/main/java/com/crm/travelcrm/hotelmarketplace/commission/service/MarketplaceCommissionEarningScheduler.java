package com.crm.travelcrm.hotelmarketplace.commission.service;

import com.crm.travelcrm.hotelmarketplace.booking.entity.PlatformHotelBooking;
import com.crm.travelcrm.hotelmarketplace.booking.enums.MarketplaceBookingStatus;
import com.crm.travelcrm.hotelmarketplace.booking.repository.PlatformHotelBookingRepository;
import com.crm.travelcrm.hotelmarketplace.commission.repository.PlatformCommissionEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Moves platform earning from {@code PENDING} to {@code EARNED} once the stay has actually happened.
 *
 * <p>Without this the ledger has a state nothing can reach: {@code accrue()} writes PENDING at
 * approval, {@code reverse()} writes a negative row on cancellation, and the only other transition
 * available was the SuperAdmin's manual {@code /settle}. Every accrual sat PENDING forever, so
 * "accrued but not yet earned" and "earned, awaiting payout" were the same number — and design §8
 * Step 9 distinguishes them precisely because the platform's revenue recognition depends on it.</p>
 *
 * <h3>Why checkout, not check-in</h3>
 * §8 Step 9 says "policy/check-in rule". This implementation earns on <b>checkout passing</b>, which
 * is the conservative reading: a guest can no-show or cut a stay short after check-in, and a
 * cancellation landing on an already-EARNED accrual forces a clawback rather than a clean reversal.
 * Waiting until the stay is over means the service was demonstrably delivered. If a commercial policy
 * later wants earlier recognition, this is the one place that changes.
 *
 * <h3>Guards</h3>
 * Only {@code CONFIRMED} bookings earn. {@code CANCEL_REQUESTED} is deliberately excluded even though
 * {@code isCommitted()} covers it — a cancellation being worked with the supplier is the opposite of
 * a stay that completed, and earning it here would mean reversing it days later.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MarketplaceCommissionEarningScheduler {

    private final PlatformCommissionEntryRepository commissionRepository;
    private final PlatformHotelBookingRepository bookingRepository;
    private final PlatformCommissionService commissionService;

    @Value("${app.marketplace.commission-earning.batch-size:200}")
    private int batchSize;

    /**
     * Daily. Nothing here is time-critical — the money is already accrued and the reporting split
     * between accrued and earned is a day-grain distinction, not an hour-grain one.
     */
    @Scheduled(fixedDelayString = "${app.marketplace.commission-earning.interval-ms:86400000}",
               initialDelayString = "${app.marketplace.commission-earning.initial-delay-ms:300000}")
    public void sweep() {
        List<Long> bookingIds = commissionRepository.findBookingIdsWithPendingAccruals(
                PageRequest.of(0, batchSize));
        if (bookingIds.isEmpty()) {
            return;
        }

        LocalDate today = LocalDate.now();
        int bookingsEarned = 0;
        int rowsEarned = 0;

        for (Long bookingId : bookingIds) {
            try {
                Optional<PlatformHotelBooking> found = bookingRepository.findById(bookingId);
                if (found.isEmpty()) {
                    continue;
                }
                PlatformHotelBooking booking = found.get();

                if (booking.getStatus() != MarketplaceBookingStatus.CONFIRMED
                        || booking.getCheckOut() == null
                        || !booking.getCheckOut().isBefore(today)) {
                    continue;   // stay not finished, or no longer a completed sale
                }

                int marked = commissionService.markEarnedForBooking(
                        bookingId, "Stay completed " + booking.getCheckOut());
                if (marked > 0) {
                    bookingsEarned++;
                    rowsEarned += marked;
                }
            } catch (RuntimeException e) {
                // One booking's failure must not strand the rest of the batch; the row stays PENDING
                // and is picked up on the next sweep.
                log.error("Could not mark commission earned for booking id {}: {}",
                        bookingId, e.getMessage(), e);
            }
        }

        if (bookingsEarned > 0) {
            log.info("Marketplace commission: {} accrual row(s) across {} booking(s) marked EARNED",
                    rowsEarned, bookingsEarned);
        }
    }
}
