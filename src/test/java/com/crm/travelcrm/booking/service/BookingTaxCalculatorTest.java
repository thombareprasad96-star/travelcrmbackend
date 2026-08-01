package com.crm.travelcrm.booking.service;

import com.crm.travelcrm.accounting.settings.entity.AccountingSettings;
import com.crm.travelcrm.accounting.settings.enums.TcsApplicability;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Booking tax is a per-TENANT decision, and getting it wrong takes real money off a real customer.
 * These are pure-math tests — no Spring context — in the style of {@code CancellationCalculatorTest}.
 *
 * <p>The case that matters most is {@link Backcompat}: before this became configurable, the platform
 * stamped a flat 5% GST + 5% TCS on every booking of every tenant. The defaults must reproduce that
 * exactly, or upgrading silently changes what existing customers are charged.
 */
class BookingTaxCalculatorTest {

    private final BookingTaxCalculator calc = new BookingTaxCalculator();

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    /** A settings row with everything at its default. */
    private AccountingSettings defaults() {
        return AccountingSettings.builder().build();
    }

    private AccountingSettings settings(boolean applyGst, String gstPct,
                                        TcsApplicability tcsMode, String tcsPct) {
        return AccountingSettings.builder()
                .applyGstOnBookings(applyGst)
                .bookingGstRatePct(bd(gstPct))
                .tcsApplicability(tcsMode)
                .bookingTcsRatePct(bd(tcsPct))
                .build();
    }

    @Nested
    @DisplayName("backward compatibility")
    class Backcompat {

        @Test
        @DisplayName("the defaults reproduce the old flat 5% + 5% exactly")
        void defaultsMatchTheOldPlatformBehaviour() {
            BookingTaxCalculator.BookingTax tax = calc.compute(bd("100000.00"), false, defaults());

            assertThat(tax.gst()).isEqualByComparingTo(bd("5000.00"));
            assertThat(tax.tcs()).isEqualByComparingTo(bd("5000.00"));
        }

        @Test
        @DisplayName("a tenant with no settings row yet still gets the old flat 5% + 5%")
        void nullSettingsFallsBackToLegacyRates() {
            // loadOrCreate should always give us a row, but a null must never mean "no tax" —
            // that would silently under-charge a customer.
            BookingTaxCalculator.BookingTax tax = calc.compute(bd("100000.00"), false, null);

            assertThat(tax.gst()).isEqualByComparingTo(bd("5000.00"));
            assertThat(tax.tcs()).isEqualByComparingTo(bd("5000.00"));
        }
    }

    @Nested
    @DisplayName("TCS applicability — the tenant's call")
    class Tcs {

        @Test
        @DisplayName("NEVER: a domestic operator collects no TCS on any booking")
        void neverCollectsNothing() {
            AccountingSettings s = settings(true, "5.00", TcsApplicability.NEVER, "5.00");

            assertThat(calc.compute(bd("100000.00"), false, s).tcs()).isEqualByComparingTo(BigDecimal.ZERO);
            // Even a booking flagged overseas — the tenant said never, and the tenant decides.
            assertThat(calc.compute(bd("100000.00"), true, s).tcs()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("OVERSEAS_ONLY: charged on an overseas package, not on a domestic one")
        void overseasOnlyRespectsTheBookingFlag() {
            AccountingSettings s = settings(true, "5.00", TcsApplicability.OVERSEAS_ONLY, "5.00");

            assertThat(calc.compute(bd("100000.00"), true,  s).tcs()).isEqualByComparingTo(bd("5000.00"));
            assertThat(calc.compute(bd("100000.00"), false, s).tcs()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("ALWAYS: charged regardless of destination")
        void alwaysIgnoresTheBookingFlag() {
            AccountingSettings s = settings(true, "5.00", TcsApplicability.ALWAYS, "5.00");

            assertThat(calc.compute(bd("100000.00"), false, s).tcs()).isEqualByComparingTo(bd("5000.00"));
            assertThat(calc.compute(bd("100000.00"), true,  s).tcs()).isEqualByComparingTo(bd("5000.00"));
        }

        @Test
        @DisplayName("a null applicability is read as ALWAYS, never as 'no tax'")
        void nullPolicyFallsBackSafely() {
            AccountingSettings s = AccountingSettings.builder().tcsApplicability(null).build();

            assertThat(calc.compute(bd("100000.00"), false, s).tcs()).isEqualByComparingTo(bd("5000.00"));
        }

        @Test
        @DisplayName("the 2026 flat 2% regime is expressible")
        void supportsTheCurrentStatutoryRate() {
            // From 1 Apr 2026 the law is a flat 2% on overseas packages with no threshold. A tenant
            // adopts it by setting the rate — no code change, no redeploy.
            AccountingSettings s = settings(true, "5.00", TcsApplicability.OVERSEAS_ONLY, "2.00");

            assertThat(calc.compute(bd("100000.00"), true, s).tcs()).isEqualByComparingTo(bd("2000.00"));
        }
    }

    @Nested
    @DisplayName("GST")
    class Gst {

        @Test
        @DisplayName("can be switched off entirely by an unregistered tenant")
        void offMeansZero() {
            AccountingSettings s = settings(false, "5.00", TcsApplicability.NEVER, "5.00");

            assertThat(calc.compute(bd("100000.00"), false, s).gst()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("honours a non-default rate, e.g. the 18% agency-fee model")
        void arbitraryRate() {
            AccountingSettings s = settings(true, "18.00", TcsApplicability.NEVER, "5.00");

            assertThat(calc.compute(bd("100000.00"), false, s).gst()).isEqualByComparingTo(bd("18000.00"));
        }

        @Test
        @DisplayName("a zero rate produces zero, not a division error")
        void zeroRate() {
            AccountingSettings s = settings(true, "0.00", TcsApplicability.NEVER, "0.00");

            assertThat(calc.compute(bd("100000.00"), false, s).gst()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Nested
    @DisplayName("rounding")
    class Rounding {

        @Test
        @DisplayName("every figure comes back at scale 2, HALF_UP")
        void scaleIsAlwaysTwo() {
            // 33333.33 * 5% = 1666.6665 → 1666.67
            AccountingSettings s = settings(true, "5.00", TcsApplicability.ALWAYS, "5.00");

            BookingTaxCalculator.BookingTax tax = calc.compute(bd("33333.33"), false, s);

            assertThat(tax.gst().scale()).isEqualTo(2);
            assertThat(tax.gst()).isEqualByComparingTo(bd("1666.67"));
            assertThat(tax.tcs()).isEqualByComparingTo(bd("1666.67"));
        }

        @Test
        @DisplayName("a fractional rate is not truncated to zero")
        void fractionalRateSurvives() {
            // The percent→base multiply must not round the RATE before applying it.
            AccountingSettings s = settings(true, "0.50", TcsApplicability.NEVER, "5.00");

            assertThat(calc.compute(bd("100000.00"), false, s).gst()).isEqualByComparingTo(bd("500.00"));
        }

        @Test
        @DisplayName("a zero or null base yields zero tax, not an error")
        void zeroBase() {
            AccountingSettings s = settings(true, "5.00", TcsApplicability.ALWAYS, "5.00");

            assertThat(calc.compute(BigDecimal.ZERO, false, s).gst()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(calc.compute(null, false, s).tcs()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }
}
