package com.crm.travelcrm.booking.service;

import com.crm.travelcrm.booking.cancellation.entity.BookingCancellation;
import com.crm.travelcrm.booking.cancellation.repository.BookingCancellationRepository;
import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.booking.repository.BookingExpenseRepository;
import com.crm.travelcrm.booking.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The single owner of a booking's profit.
 *
 * <pre>
 *   totalInternalCosts = SUM(active INTERNAL expense rows)
 *   netProfit          = customerAmount − vendorCost − totalInternalCosts
 * </pre>
 *
 * <p><b>Why a service and not a report query.</b> {@code netProfit} is read from six different
 * places (booking stats, page summary, the revenue report row + totals, the dashboard hero card and
 * the per-user leaderboard). Computing it inline in each would give the product six chances to
 * drift, and it has already happened once — {@code DevDataSeeder} invented its own 30%-of-amount
 * formula, and the cancellation engine a second one. So the figure is denormalised onto the
 * booking, every reader reads that one column, and this class is the only thing that writes it.
 *
 * <p><b>Why it is safe to call anywhere.</b> {@link #apply(Booking)} is idempotent and recomputes
 * from source every time — it never accumulates a delta, so calling it twice for one event cannot
 * double-count, and calling it on an unchanged booking is a no-op. That matters more than it looks:
 * {@code Booking} is {@code @Audited}, so a write that changed nothing would still mint an Envers
 * revision and bump the {@code @Version} column, filling the financial audit trail with machine
 * noise and turning harmless concurrent edits into 409s. Hence the equality guard — the entity is
 * only dirtied when a figure genuinely moved. This mirrors
 * {@code SubAgentCommissionService.syncForBooking}, the established "money changed, reconcile the
 * derived figure" idiom in this module.
 *
 * <p><b>Why an explicit call and not an event.</b> Every cross-module reaction in this codebase is a
 * plain synchronous {@code @EventListener}, and {@code NotifyEventListener} clears
 * {@code TenantContext} in a {@code finally} on the publisher's own thread — after which
 * {@code TenantFilterAspect} fails OPEN on the null tenant. A recalculation listener that happened
 * to run after such a publish would query unfiltered across tenants. An explicit call has no
 * ordering hazard, so it is the deliberate choice here.
 *
 * <p>Scale is 2 / HALF_UP at every boundary, matching {@code numeric(12,2)} on the columns and the
 * convention every other financial calculator in the module follows.
 */
@Service
@RequiredArgsConstructor
public class BookingProfitService {

    private static final Logger log = LogManager.getLogger(BookingProfitService.class);

    private static final int SCALE = 2;

    private final BookingRepository             bookingRepository;
    private final BookingExpenseRepository      expenseRepository;
    private final BookingCancellationRepository cancellationRepository;

    /**
     * Recompute this booking's {@code totalInternalCosts} and {@code netProfit} from source and,
     * only if either moved, persist them.
     *
     * <p>Takes an already-loaded, already-authorised booking: every caller has resolved it through
     * its own guarded lookup, and re-reading it here would both waste a query and risk operating on
     * a row the caller was never allowed to touch.
     *
     * @return true when a figure actually changed (and the row was saved)
     */
    @Transactional
    public boolean apply(Booking booking) {
        BigDecimal internalCosts = scale(expenseRepository.sumInternalCosts(booking.getId()));

        // A CANCELLED booking's profit is not the active formula. Nothing was delivered, so there is
        // no customerAmount to earn — what the agency actually made is the charge it retained under
        // the cancellation policy, less the costs it could not recover. Applying
        // customerAmount − vendorCost − internalCosts to a cancelled row would report the margin of
        // a trip that never happened, and would do so the moment anyone edited an expense on it.
        //
        // The POLICY side stays frozen (finalChargeBase and sunkVendorCost are read from the record,
        // never recomputed) — that is the anti-retroactivity guarantee, and editing a cost must not
        // reopen how the customer was charged. Only the cost side moves, because it genuinely did.
        BookingCancellation cancellation = booking.getId() > 0
                ? cancellationRepository.findByBookingIdAndDeletedAtIsNull(booking.getId()).orElse(null)
                : null;

        BigDecimal netProfit = cancellation != null
                ? scale(nz(cancellation.getFinalChargeBase())
                        .subtract(nz(cancellation.getSunkVendorCost()))
                        .subtract(internalCosts))
                : scale(nz(booking.getCustomerAmount())
                        .subtract(nz(booking.getVendorCost()))
                        .subtract(internalCosts));

        // compareTo, not equals: BigDecimal.equals() is scale-sensitive, so 0 and 0.00 would read as
        // a change and dirty the row on every single call.
        boolean changed = internalCosts.compareTo(nz(booking.getTotalInternalCosts())) != 0
                || netProfit.compareTo(nz(booking.getNetProfit())) != 0;
        if (!changed) {
            return false;
        }

        booking.setTotalInternalCosts(internalCosts);
        booking.setNetProfit(netProfit);
        bookingRepository.save(booking);

        // Keep the cancellation record's own copy in step, so the booking and the credit-note trail
        // can never quote two different cancellation margins for the same booking.
        if (cancellation != null) {
            cancellation.setSunkInternalCosts(internalCosts);
            cancellation.setRevisedNetProfit(netProfit);
            cancellationRepository.save(cancellation);
        }

        log.info("Profit recalculated for booking {} -> internalCosts {}, netProfit {}",
                booking.getBookingCode(), internalCosts, netProfit);
        return true;
    }

    /**
     * Recompute the figures onto the entity WITHOUT saving — for callers that are mid-build on a
     * booking they are about to persist themselves (create and lead→booking conversion), where an
     * extra save would be a redundant round trip and, on create, would run before the row exists.
     */
    public void applyInMemory(Booking booking, BigDecimal internalCosts) {
        BigDecimal costs = scale(internalCosts);
        booking.setTotalInternalCosts(costs);
        booking.setNetProfit(scale(
                nz(booking.getCustomerAmount()).subtract(nz(booking.getVendorCost())).subtract(costs)));
    }

    /** The {@code totalInternalCosts} term on its own — active INTERNAL rows only. */
    @Transactional(readOnly = true)
    public BigDecimal internalCostsOf(Long bookingId) {
        return scale(expenseRepository.sumInternalCosts(bookingId));
    }

    private static BigDecimal scale(BigDecimal v) {
        return nz(v).setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
