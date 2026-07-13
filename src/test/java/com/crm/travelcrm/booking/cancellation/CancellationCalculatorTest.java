package com.crm.travelcrm.booking.cancellation;

import com.crm.travelcrm.booking.cancellation.dto.CancellationQuote;
import com.crm.travelcrm.booking.cancellation.entity.CancellationPolicy;
import com.crm.travelcrm.booking.cancellation.entity.CancellationPolicyBand;
import com.crm.travelcrm.booking.cancellation.enums.CancellationPolicyLevel;
import com.crm.travelcrm.booking.cancellation.enums.DeductionType;
import com.crm.travelcrm.booking.cancellation.service.CancellationCalculator;
import com.crm.travelcrm.booking.entity.Booking;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure-math tests for {@link CancellationCalculator}. No Spring context — the calculator is a pure
 * function, so these run fast and pin every high-risk rule surfaced in design review.
 */
class CancellationCalculatorTest {

    private final CancellationCalculator calc = new CancellationCalculator();

    private static final LocalDate CANCEL_ON = LocalDate.of(2026, 1, 1);

    // Seeded-style tiered default: >=30 →10%, 15–29 →25%, 7–14 →50%, <7/no-show →100%.
    private CancellationPolicy tieredPolicy(boolean gstApplicable, boolean tcsRefundable) {
        CancellationPolicy p = CancellationPolicy.builder()
                .name("test")
                .level(CancellationPolicyLevel.COMPANY)
                .version(1).active(true)
                .effectiveFrom(LocalDate.of(1970, 1, 1))
                .gstOnChargeApplicable(gstApplicable)
                .tcsRefundable(tcsRefundable)
                .bands(List.of(
                        band(30, DeductionType.PERCENT, "10"),
                        band(15, DeductionType.PERCENT, "25"),
                        band(7, DeductionType.PERCENT, "50"),
                        band(0, DeductionType.PERCENT, "100")))
                .build();
        p.setPublicId(UUID.randomUUID());
        return p;
    }

    private CancellationPolicy singleBandPolicy(DeductionType type, String value) {
        CancellationPolicy p = CancellationPolicy.builder()
                .name("flat").level(CancellationPolicyLevel.COMPANY).version(1).active(true)
                .effectiveFrom(LocalDate.of(1970, 1, 1)).gstOnChargeApplicable(true).tcsRefundable(true)
                .bands(List.of(band(0, type, value)))
                .build();
        p.setPublicId(UUID.randomUUID());
        return p;
    }

    private static CancellationPolicyBand band(int minDays, DeductionType type, String value) {
        return CancellationPolicyBand.builder()
                .minDaysBeforeDeparture(minDays).deductionType(type).deductionValue(new BigDecimal(value)).build();
    }

    /** customerAmount / gst / tcs / totalPayable / vendorCost / paidAmount, travelDate = CANCEL_ON + daysToTravel. */
    private Booking booking(String base, String gst, String tcs, String total,
                            String vendor, String paid, int daysToTravel) {
        return Booking.builder()
                .publicId(UUID.randomUUID())
                .customerAmount(new BigDecimal(base))
                .gst(new BigDecimal(gst))
                .tcs(new BigDecimal(tcs))
                .totalPayable(new BigDecimal(total))
                .vendorCost(new BigDecimal(vendor))
                .paidAmount(new BigDecimal(paid))
                .travelDate(CANCEL_ON.plusDays(daysToTravel))
                .bookingDate(CANCEL_ON)
                .build();
    }

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    /** Reconciliation invariant that must hold for EVERY quote: what's kept + what's returned = what was paid. */
    private void assertReconciles(CancellationQuote q) {
        BigDecimal recomputed = q.getChargeBase().add(q.getGstOnCharge()).add(q.getTcsRetained()).add(q.getRefundDue());
        assertEquals(0, q.getPaidAmount().compareTo(recomputed),
                "chargeBase + gstOnCharge + tcsRetained + refundDue must equal paidAmount");
    }

    @Test
    void standardPartialPayment_25pctBand() {
        // 20 days out → 25% band. base 100k, gst 5k, tcs 5k, paid 60k.
        Booking b = booking("100000", "5000", "5000", "110000", "70000", "60000", 20);
        CancellationQuote q = calc.calculate(b, tieredPolicy(true, true), CANCEL_ON, null, null);

        assertEquals(15, q.getAppliedBandMinDays());
        assertEquals(bd("25000.00"), q.getChargeBase());
        assertEquals(bd("1250.00"), q.getGstOnCharge());        // 5000 * 25000/100000
        assertEquals(bd("0.00"), q.getTcsRetained());           // TCS refundable
        assertEquals(bd("26250.00"), q.getTotalRetained());
        assertEquals(bd("33750.00"), q.getRefundDue());
        assertEquals(bd("33750.00"), q.getRefundToCustomer());
        assertEquals(bd("0.00"), q.getCustomerBalanceOwed());
        assertFalse(q.isCustomerOwes());
        assertReconciles(q);
    }

    @Test
    void negativeRefund_customerOwes_neverClamped() {
        // 3 days out → 100% band. paid only 50k of a 110k booking.
        Booking b = booking("100000", "5000", "5000", "110000", "70000", "50000", 3);
        CancellationQuote q = calc.calculate(b, tieredPolicy(true, true), CANCEL_ON, null, null);

        assertEquals(bd("100000.00"), q.getChargeBase());
        assertEquals(bd("5000.00"), q.getGstOnCharge());        // full gst retained at 100%
        assertEquals(bd("105000.00"), q.getTotalRetained());
        assertEquals(bd("-55000.00"), q.getRefundDue());        // SIGNED — not clamped to zero
        assertEquals(bd("0.00"), q.getRefundToCustomer());
        assertEquals(bd("55000.00"), q.getCustomerBalanceOwed());
        assertTrue(q.isCustomerOwes());
        assertReconciles(q);
    }

    @Test
    void noShow_pastTravelDate_retainsFull_notRefund() {
        // travel was 8 days BEFORE the cancel date → effectiveDays 0 → 100% band, never a full refund.
        Booking b = booking("100000", "5000", "5000", "110000", "70000", "110000", -8);
        CancellationQuote q = calc.calculate(b, tieredPolicy(true, true), CANCEL_ON, null, null);

        assertEquals(-8, q.getDaysBeforeDeparture());
        assertEquals(0, q.getEffectiveDays());
        assertEquals(0, q.getAppliedBandMinDays());
        assertEquals(bd("100000.00"), q.getChargeBase());       // retains 100%, does NOT refund a no-show
        assertEquals(bd("105000.00"), q.getTotalRetained());
        assertEquals(bd("5000.00"), q.getRefundDue());          // only the refundable TCS comes back
        assertReconciles(q);
    }

    @Test
    void flatBandExceedingBase_isClampedToBase() {
        // Fat-fingered flat fee 120k on a 100k booking must clamp to 100k, not invent a debt above base.
        Booking b = booking("100000", "5000", "5000", "110000", "70000", "110000", 3);
        CancellationQuote q = calc.calculate(b, singleBandPolicy(DeductionType.FLAT, "120000"), CANCEL_ON, null, null);

        assertEquals(bd("100000.00"), q.getChargeBase());       // clamped to base
        assertTrue(q.getTotalRetained().compareTo(b.getTotalPayable()) <= 0,
                "totalRetained must never exceed totalPayable");
        assertReconciles(q);
    }

    @Test
    void hundredPercentBand_fullyPaid_refundsExactlyTheTcs() {
        // Invariant from the money-lens: 110k paid, 100% charge, TCS refundable → refund == the 5k TCS.
        Booking b = booking("100000", "5000", "5000", "110000", "70000", "110000", 3);
        CancellationQuote q = calc.calculate(b, tieredPolicy(true, true), CANCEL_ON, null, null);
        assertEquals(bd("5000.00"), q.getRefundDue());
        assertReconciles(q);
    }

    @Test
    void gstOnCharge_isProportionalToStoredGst_notALiveRate() {
        // A booking taxed at an ODD stored gst (7,000 on 100,000) must retain gst proportional to THAT,
        // not to any global 5%/18% rate. 25% band → 7000 * 0.25 = 1750.
        Booking b = booking("100000", "7000", "5000", "112000", "70000", "112000", 20);
        CancellationQuote q = calc.calculate(b, tieredPolicy(true, true), CANCEL_ON, null, null);
        assertEquals(bd("1750.00"), q.getGstOnCharge());
        assertReconciles(q);
    }

    @Test
    void gstNotApplicable_and_tcsNotRefundable_switches() {
        // gstOnChargeApplicable=false → retain no GST. tcsRefundable=false → retain TCS proportionally.
        Booking b = booking("100000", "5000", "5000", "110000", "70000", "110000", 20);
        CancellationQuote q = calc.calculate(b, tieredPolicy(false, false), CANCEL_ON, null, null);
        assertEquals(bd("0.00"), q.getGstOnCharge());
        assertEquals(bd("1250.00"), q.getTcsRetained());        // 5000 * 25000/100000
        assertReconciles(q);
    }

    @Test
    void overrideWaiver_zeroCharge_refundsEverythingPaid() {
        Booking b = booking("100000", "5000", "5000", "110000", "70000", "60000", 3);
        CancellationQuote q = calc.calculate(b, tieredPolicy(true, true), CANCEL_ON, BigDecimal.ZERO, null);
        assertTrue(q.isOverrideApplied());
        assertEquals(bd("0.00"), q.getChargeBase());
        assertEquals(bd("0.00"), q.getTotalRetained());
        assertEquals(bd("60000.00"), q.getRefundDue());         // full paid amount refunded
        assertReconciles(q);
    }

    @Test
    void noPolicy_yieldsZeroChargeFlagged() {
        Booking b = booking("100000", "5000", "5000", "110000", "70000", "60000", 20);
        CancellationQuote q = calc.calculate(b, null, CANCEL_ON, null, null);
        assertTrue(q.isNoPolicy());
        assertEquals(bd("0.00"), q.getChargeBase());
        assertEquals(bd("60000.00"), q.getRefundDue());
        assertReconciles(q);
    }

    @Test
    void revisedProfit_usesRetainedBaseMinusSunkVendorCost() {
        // 20 days → 25% → chargeBase 25k. Vendor 70k fully sunk (no recovery) → profit 25k - 70k = -45k.
        Booking b = booking("100000", "5000", "5000", "110000", "70000", "60000", 20);
        CancellationQuote q = calc.calculate(b, tieredPolicy(true, true), CANCEL_ON, null, null);
        assertEquals(bd("70000.00"), q.getSunkVendorCost());
        assertEquals(bd("-45000.00"), q.getRevisedNetProfit());

        // With 40k recovered → sunk 30k → profit 25k - 30k = -5k.
        CancellationQuote q2 = calc.calculate(b, tieredPolicy(true, true), CANCEL_ON, null, bd("40000"));
        assertEquals(bd("30000.00"), q2.getSunkVendorCost());
        assertEquals(bd("-5000.00"), q2.getRevisedNetProfit());
    }
}
