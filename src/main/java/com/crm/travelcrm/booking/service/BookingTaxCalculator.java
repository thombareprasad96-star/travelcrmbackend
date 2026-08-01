package com.crm.travelcrm.booking.service;

import com.crm.travelcrm.accounting.settings.entity.AccountingSettings;
import com.crm.travelcrm.accounting.settings.enums.TcsApplicability;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Computes the GST and TCS shown on a booking, from the TENANT's own tax settings.
 *
 * <p>A pure function of its inputs — no repository, no {@code TenantContext}, no clock — so it can
 * be unit-tested exhaustively without a Spring context, matching {@code CancellationCalculator} and
 * {@code ExpenseSettlementCalculator}.
 *
 * <p><b>What this replaced.</b> The rates used to be two platform-wide properties
 * ({@code app.booking.gst-rate} / {@code app.booking.tcs-rate}), a flat 5% + 5% applied to every
 * booking of every tenant. That is wrong in both directions for an Indian travel agency: TCS under
 * s.206C(1G)/394 does not reach domestic packages at all, so a domestic operator was over-collecting
 * tax from every customer; and GST varies by what is being sold. The rates are a property of the
 * tenant's registration and product mix, so the tenant owns them.
 *
 * <p><b>Both taxes sit OUTSIDE profit, and that is deliberate.</b> {@code customerAmount} is the
 * pre-tax base; GST and TCS are added ON TOP to form {@code totalPayable}. Neither is agency income
 * — GST is output tax owed to the government and TCS is the customer's own tax credit — so
 * {@code netProfit} is computed on {@code customerAmount} and never sees either figure.
 *
 * <p>Scale 2 / HALF_UP at every boundary, matching {@code numeric(12,2)} on the columns.
 */
@Component
public class BookingTaxCalculator {

    private static final int SCALE = 2;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /** The two tax figures for one booking. */
    public record BookingTax(BigDecimal gst, BigDecimal tcs) {}

    /**
     * @param customerAmount     the pre-tax base the customer is being charged
     * @param overseasTourPackage whether this booking is an overseas tour package; only consulted
     *                            when the tenant's policy is {@link TcsApplicability#OVERSEAS_ONLY}
     * @param settings           the tenant's settings; {@code null} falls back to the historical
     *                           flat 5% + 5% so a tenant whose settings row has not been created yet
     *                           behaves exactly as it did before this became configurable
     */
    public BookingTax compute(BigDecimal customerAmount,
                              boolean overseasTourPackage,
                              AccountingSettings settings) {
        BigDecimal base = scale(customerAmount);

        boolean gstApplies    = settings == null || settings.isApplyGstOnBookings();
        BigDecimal gstRatePct = settings != null ? nz(settings.getBookingGstRatePct()) : new BigDecimal("5.00");
        BigDecimal gst = gstApplies ? percentOf(base, gstRatePct) : zero();

        TcsApplicability policy = settings != null && settings.getTcsApplicability() != null
                ? settings.getTcsApplicability()
                : TcsApplicability.ALWAYS;
        BigDecimal tcsRatePct = settings != null ? nz(settings.getBookingTcsRatePct()) : new BigDecimal("5.00");
        BigDecimal tcs = tcsApplies(policy, overseasTourPackage) ? percentOf(base, tcsRatePct) : zero();

        return new BookingTax(gst, tcs);
    }

    private static boolean tcsApplies(TcsApplicability policy, boolean overseasTourPackage) {
        return switch (policy) {
            case NEVER         -> false;
            case OVERSEAS_ONLY -> overseasTourPackage;
            case ALWAYS        -> true;
        };
    }

    /**
     * Rate is a PERCENT (5.00 ⇒ 5%), so divide by 100. Rounded once, at the end — an intermediate
     * rounding would make the figure disagree with the same rate applied to the same base elsewhere.
     */
    private static BigDecimal percentOf(BigDecimal base, BigDecimal ratePct) {
        if (ratePct.signum() <= 0) return zero();
        return base.multiply(ratePct).divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale(BigDecimal v) {
        return nz(v).setScale(SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(SCALE);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
