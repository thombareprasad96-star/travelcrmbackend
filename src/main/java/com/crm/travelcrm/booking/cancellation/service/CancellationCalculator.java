package com.crm.travelcrm.booking.cancellation.service;

import com.crm.travelcrm.booking.cancellation.dto.CancellationQuote;
import com.crm.travelcrm.booking.cancellation.entity.CancellationPolicy;
import com.crm.travelcrm.booking.cancellation.entity.CancellationPolicyBand;
import com.crm.travelcrm.booking.cancellation.enums.DeductionType;
import com.crm.travelcrm.booking.entity.Booking;
import com.crm.travelcrm.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Pure, side-effect-free cancellation math. Given a booking, the policy version that governs it, the
 * cancel date, an optional charge override and an optional vendor-recovery figure, it produces the
 * fully itemised {@link CancellationQuote}. No persistence, no context, no clock reads beyond the
 * supplied {@code cancelDate} — so it is safe from a read-only preview and trivially unit-testable.
 *
 * <p>Design invariants (each closes a specific failure mode found in design review):
 * <ul>
 *   <li><b>Floor-anchored band selection.</b> The day-count is clamped at zero, so a same-day cancel
 *       or an already-departed no-show always lands on the {@code minDays == 0} band — a no-show can
 *       never fall through to a zero charge and a full refund.</li>
 *   <li><b>Charge clamped to the base fare.</b> A FLAT (or ≥100% PERCENT) band can never retain more
 *       than the booking's own value, so a fat-fingered flat fee can't invent a debt.</li>
 *   <li><b>Tax derived from STORED figures.</b> Retained GST/TCS are a proportion of the booking's
 *       own {@code gst}/{@code tcs} amounts, never a live rate — structurally we can never retain
 *       more tax than was collected, even if the global rate changed after booking.</li>
 *   <li><b>Signed settlement.</b> {@code refundDue} is never clamped; a customer who paid less than
 *       the retention genuinely owes the difference.</li>
 * </ul>
 */
@Component
public class CancellationCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * @param booking           the booking being cancelled (source of the stored money figures)
     * @param policy            the governing policy version, or {@code null} → a NO_POLICY quote (charge 0)
     * @param cancelDate        the effective cancellation date (defaults to travel-relative "today" if null)
     * @param overrideChargeBase optional manual retained base (a waiver = 0); clamped to [0, base]
     * @param vendorRecoverable  amount of vendorCost the agency expects to recover (default 0 = fully sunk)
     */
    public CancellationQuote calculate(Booking booking,
                                       CancellationPolicy policy,
                                       LocalDate cancelDate,
                                       BigDecimal overrideChargeBase,
                                       BigDecimal vendorRecoverable) {
        if (booking.getTravelDate() == null) {
            throw new BusinessException(
                    "This booking has no travel date, so a cancellation charge cannot be computed. "
                            + "Set a travel date or cancel with an explicit override.",
                    HttpStatus.BAD_REQUEST);
        }
        LocalDate onDate = cancelDate != null ? cancelDate : LocalDate.now();

        BigDecimal base = nz(booking.getCustomerAmount());
        BigDecimal paid = nz(booking.getPaidAmount());
        BigDecimal recoverable = clampToRange(nz(vendorRecoverable), BigDecimal.ZERO, nz(booking.getVendorCost()));

        int rawDays = (int) ChronoUnit.DAYS.between(onDate, booking.getTravelDate());
        int effectiveDays = Math.max(0, rawDays);

        CancellationQuote.CancellationQuoteBuilder q = CancellationQuote.builder()
                .bookingPublicId(booking.getPublicId())
                .travelDate(booking.getTravelDate())
                .cancelDate(onDate)
                .daysBeforeDeparture(rawDays)
                .effectiveDays(effectiveDays)
                .baseConsidered(base)
                .paidAmount(paid)
                .vendorRecoverable(recoverable);

        // ── Retained base ────────────────────────────────────────────────────
        BigDecimal systemChargeBase;
        boolean gstApplicable;
        boolean tcsRefundable;
        if (policy == null) {
            // No resolvable policy: retain nothing by default; the caller must gate this behind an
            // elevated override rather than let it silently become a full refund.
            systemChargeBase = BigDecimal.ZERO;
            gstApplicable = true;
            tcsRefundable = true;
            q.noPolicy(true);
        } else {
            CancellationPolicyBand band = selectBand(policy, effectiveDays);
            systemChargeBase = clampToRange(chargeFromBand(band, base), BigDecimal.ZERO, base);
            gstApplicable = Boolean.TRUE.equals(policy.getGstOnChargeApplicable());
            tcsRefundable = Boolean.TRUE.equals(policy.getTcsRefundable());
            q.appliedBandMinDays(band.getMinDaysBeforeDeparture())
             .appliedDeductionType(band.getDeductionType())
             .appliedDeductionValue(band.getDeductionValue());
        }
        systemChargeBase = scale(systemChargeBase);

        BigDecimal chargeBase = systemChargeBase;
        boolean overrideApplied = false;
        if (overrideChargeBase != null) {
            chargeBase = scale(clampToRange(overrideChargeBase, BigDecimal.ZERO, base));
            overrideApplied = chargeBase.compareTo(systemChargeBase) != 0;
        }

        // ── Retained tax (proportional to the retained base, from STORED amounts) ──
        BigDecimal gstOnCharge = gstApplicable
                ? proportion(nz(booking.getGst()), chargeBase, base) : BigDecimal.ZERO.setScale(2);
        BigDecimal tcsRetained = tcsRefundable
                ? BigDecimal.ZERO.setScale(2) : proportion(nz(booking.getTcs()), chargeBase, base);

        BigDecimal totalRetained = scale(chargeBase.add(gstOnCharge).add(tcsRetained));

        // ── Settlement (signed) ───────────────────────────────────────────────
        BigDecimal refundDue = scale(paid.subtract(totalRetained));
        BigDecimal refundToCustomer = refundDue.signum() > 0 ? refundDue : BigDecimal.ZERO.setScale(2);
        BigDecimal customerBalanceOwed = refundDue.signum() < 0 ? refundDue.negate() : BigDecimal.ZERO.setScale(2);

        // ── Agency P&L ─────────────────────────────────────────────────────────
        BigDecimal sunkVendorCost = scale(nz(booking.getVendorCost()).subtract(recoverable));
        BigDecimal revisedNetProfit = scale(chargeBase.subtract(sunkVendorCost));

        return q.systemComputedChargeBase(systemChargeBase)
                .chargeBase(chargeBase)
                .overrideApplied(overrideApplied)
                .gstOnChargeApplicable(gstApplicable)
                .tcsRefundable(tcsRefundable)
                .gstOnCharge(gstOnCharge)
                .tcsRetained(tcsRetained)
                .totalRetained(totalRetained)
                .refundDue(refundDue)
                .refundToCustomer(refundToCustomer)
                .customerBalanceOwed(customerBalanceOwed)
                .customerOwes(refundDue.signum() < 0)
                .sunkVendorCost(sunkVendorCost)
                .revisedNetProfit(revisedNetProfit)
                .build();
    }

    /**
     * Select the band whose {@code minDaysBeforeDeparture} is the greatest value still ≤
     * {@code effectiveDays}. The policy is validated at save to include a floor band at 0, so a match
     * is guaranteed; a missing floor (e.g. a hand-crafted legacy row) fails loudly rather than
     * silently charging zero.
     */
    private CancellationPolicyBand selectBand(CancellationPolicy policy, int effectiveDays) {
        CancellationPolicyBand best = null;
        for (CancellationPolicyBand band : policy.getBands()) {
            if (band.getMinDaysBeforeDeparture() <= effectiveDays
                    && (best == null || band.getMinDaysBeforeDeparture() > best.getMinDaysBeforeDeparture())) {
                best = band;
            }
        }
        if (best == null) {
            throw new BusinessException(
                    "Cancellation policy " + policy.getPublicId() + " has no band covering "
                            + effectiveDays + " days before departure (missing floor band).",
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return best;
    }

    private BigDecimal chargeFromBand(CancellationPolicyBand band, BigDecimal base) {
        if (band.getDeductionType() == DeductionType.PERCENT) {
            return base.multiply(nz(band.getDeductionValue())).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        }
        return nz(band.getDeductionValue());   // FLAT
    }

    /** {@code total * (part / whole)} computed as {@code (total * part) / whole}, scaled to paise. */
    private BigDecimal proportion(BigDecimal total, BigDecimal part, BigDecimal whole) {
        if (whole.signum() == 0) return BigDecimal.ZERO.setScale(2);
        return total.multiply(part).divide(whole, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal clampToRange(BigDecimal v, BigDecimal lo, BigDecimal hi) {
        if (v.compareTo(lo) < 0) return lo;
        if (v.compareTo(hi) > 0) return hi;
        return v;
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}