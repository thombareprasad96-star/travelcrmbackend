package com.crm.travelcrm.fleet.money;

import com.crm.travelcrm.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The first fleet tests in this repository. Fleet had none, which for a module about to move money
 * is the release blocker — not the standalone boundary.
 */
class FleetMoneyCalculatorTest {

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    @Nested
    @DisplayName("base-amount conversion")
    class Conversion {

        @Test
        @DisplayName("INR passes through at rate 1, scaled to paise")
        void inrPassesThrough() {
            assertThat(FleetMoneyCalculator.toBase(n("640"), BigDecimal.ONE))
                    .isEqualByComparingTo("640.00");
        }

        @Test
        @DisplayName("NPR 2500 at 0.625 is exactly Rs 1562.50 — the Bhansar case")
        void nepalConversion() {
            assertThat(FleetMoneyCalculator.toBase(n("2500"), n("0.62500000")))
                    .isEqualByComparingTo("1562.50");
        }

        @Test
        @DisplayName("rate precision is not lost: 0.625 must not degrade to 0.63")
        void ratePrecisionSurvives() {
            // The whole reason fx_rate is numeric(18,8) and not numeric(14,2). At 2dp the rate
            // rounds to 0.63 and NPR 100,000 converts to 63,000 instead of 62,500 — Rs 500 wrong
            // on a single receipt.
            BigDecimal atFullPrecision = FleetMoneyCalculator.toBase(n("100000"), n("0.62500000"));
            BigDecimal ifRateWereRounded = FleetMoneyCalculator.toBase(n("100000"), n("0.63"));

            assertThat(atFullPrecision).isEqualByComparingTo("62500.00");
            assertThat(ifRateWereRounded).isEqualByComparingTo("63000.00");
        }

        @Test
        @DisplayName("HALF_UP at 2dp, applied once")
        void roundingIsHalfUp() {
            // 1001 * 0.625 = 625.625 -> 625.63
            assertThat(FleetMoneyCalculator.toBase(n("1001"), n("0.625")))
                    .isEqualByComparingTo("625.63");
        }

        @Test
        @DisplayName("per-row rounding is the definition — a re-converted total will differ, and that is expected")
        void perRowIsCanonical() {
            // Seven NPR 1001 rows. Per-row: 7 x 625.63 = 4379.41. Whole: 7007 x 0.625 = 4379.375 -> 4379.38.
            // Both are defensible; only one can be canonical. Reports MUST sum stored base_amount,
            // never re-convert an aggregate, or two screens disagree by three paise forever.
            BigDecimal perRow = BigDecimal.ZERO;
            for (int i = 0; i < 7; i++) {
                perRow = perRow.add(FleetMoneyCalculator.toBase(n("1001"), n("0.625")));
            }
            BigDecimal reconverted = FleetMoneyCalculator.toBase(n("7007"), n("0.625"));

            assertThat(perRow).isEqualByComparingTo("4379.41");
            assertThat(reconverted).isEqualByComparingTo("4379.38");
            assertThat(perRow).isNotEqualByComparingTo(reconverted);
        }

        @Test
        @DisplayName("a non-positive amount is rejected, not normalised — corrections go through reversals")
        void rejectsNonPositiveAmount() {
            assertThatThrownBy(() -> FleetMoneyCalculator.toBase(BigDecimal.ZERO, BigDecimal.ONE))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("greater than 0");

            assertThatThrownBy(() -> FleetMoneyCalculator.toBase(n("-500"), BigDecimal.ONE))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("a non-positive rate is rejected")
        void rejectsNonPositiveRate() {
            assertThatThrownBy(() -> FleetMoneyCalculator.toBase(n("100"), BigDecimal.ZERO))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Exchange rate");
        }
    }

    @Nested
    @DisplayName("rate resolution")
    class RateResolution {

        @Test
        @DisplayName("INR is always exactly 1 — never a stored rate")
        void inrIsAlwaysOne() {
            // Even with a nonsense trip rate present, an INR row must not be scaled by it.
            assertThat(FleetMoneyCalculator.resolveRate("INR", n("0.625"))).isEqualByComparingTo("1");
            assertThat(FleetMoneyCalculator.resolveRate(null, n("0.625"))).isEqualByComparingTo("1");
            assertThat(FleetMoneyCalculator.resolveRate("  ", null)).isEqualByComparingTo("1");
        }

        @Test
        @DisplayName("a foreign currency with no trip rate is a clear 400, not a silent 1:1")
        void foreignCurrencyNeedsARate() {
            assertThatThrownBy(() -> FleetMoneyCalculator.resolveRate("NPR", null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("NPR")
                    .extracting(e -> ((BusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("the trip's rate is used for a foreign currency")
        void usesTripRate() {
            assertThat(FleetMoneyCalculator.resolveRate("npr", n("0.625")))
                    .isEqualByComparingTo("0.625");
        }
    }

    @Nested
    @DisplayName("Indian financial year")
    class FinancialYear {

        @Test
        @DisplayName("April starts a new FY; March belongs to the previous one")
        void fyBoundary() {
            assertThat(FleetMoneyCalculator.financialYearOf(LocalDate.of(2026, 4, 1))).isEqualTo(2026);
            assertThat(FleetMoneyCalculator.financialYearOf(LocalDate.of(2027, 3, 31))).isEqualTo(2026);
            assertThat(FleetMoneyCalculator.financialYearOf(LocalDate.of(2027, 4, 1))).isEqualTo(2027);
            assertThat(FleetMoneyCalculator.financialYearOf(LocalDate.of(2026, 1, 15))).isEqualTo(2025);
        }
    }
}
