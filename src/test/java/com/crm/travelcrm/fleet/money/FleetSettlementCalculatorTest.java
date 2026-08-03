package com.crm.travelcrm.fleet.money;

import com.crm.travelcrm.fleet.enums.FleetCashDirection;
import com.crm.travelcrm.fleet.money.FleetSettlementCalculator.CashMovement;
import com.crm.travelcrm.fleet.money.FleetSettlementCalculator.Settlement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The driver settlement arithmetic, pinned in every sign combination.
 *
 * <p>Several of these tests exist because an adversarial review found the corresponding bug in the
 * design before it was built — the bata double-subtraction and the multi-driver netting in
 * particular. They are here so those cannot come back silently.
 */
class FleetSettlementCalculatorTest {

    private static BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    private static CashMovement mv(FleetCashDirection d, String amount) {
        return new CashMovement(d, n(amount));
    }

    private static Settlement settle(List<CashMovement> movements, String spend, String allowance) {
        return FleetSettlementCalculator.settle(movements, n(spend), n(allowance));
    }

    @Nested
    @DisplayName("the canonical trip")
    class CanonicalTrip {

        @Test
        @DisplayName("8000 advance, 5000 spent, 1200 bata → driver returns 1800")
        void classicHisaab() {
            Settlement s = settle(List.of(mv(FleetCashDirection.ADVANCE_OUT, "8000")), "5000", "1200");

            assertThat(s.netDueFromDriver()).isEqualByComparingTo("1800.00");
            assertThat(s.isSquared()).isFalse();
            assertThat(s.advanceTotal()).isEqualByComparingTo("8000.00");
        }

        @Test
        @DisplayName("after he hands back the 1800, the settlement squares at exactly zero")
        void squaresAfterReturn() {
            Settlement s = settle(List.of(
                    mv(FleetCashDirection.ADVANCE_OUT, "8000"),
                    mv(FleetCashDirection.CASH_RETURN, "1800")), "5000", "1200");

            assertThat(s.netDueFromDriver()).isEqualByComparingTo("0.00");
            assertThat(s.isSquared()).isTrue();
        }

        @Test
        @DisplayName("BATA IS NOT SUBTRACTED TWICE — the bug that would hand him 1200 he was never owed")
        void allowanceIsDischargedExactlyOnce() {
            // The trap: a DRIVER_BATA expense row marked DRIVER_CASH is a real trip cost, so it is
            // tempting to let it flow into driverCashSpend as well. Then 8000 - 6200 - 1200 = 600,
            // the driver returns 600 instead of 1800, and the sheet says "balanced".
            Settlement correct = settle(List.of(mv(FleetCashDirection.ADVANCE_OUT, "8000")), "5000", "1200");
            Settlement ifBataLeakedIntoSpend =
                    settle(List.of(mv(FleetCashDirection.ADVANCE_OUT, "8000")), "6200", "1200");

            assertThat(correct.netDueFromDriver()).isEqualByComparingTo("1800.00");
            assertThat(ifBataLeakedIntoSpend.netDueFromDriver()).isEqualByComparingTo("600.00");

            // Rs 1200 per trip. The caller MUST exclude system-computed types from driverCashSpend.
            assertThat(correct.netDueFromDriver().subtract(ifBataLeakedIntoSpend.netDueFromDriver()))
                    .isEqualByComparingTo("1200.00");
        }
    }

    @Nested
    @DisplayName("customer money")
    class CustomerMoney {

        @Test
        @DisplayName("collections increase what he holds; depositing them discharges it")
        void collectionThenDeposit() {
            Settlement collected = settle(List.of(
                    mv(FleetCashDirection.ADVANCE_OUT, "8000"),
                    mv(FleetCashDirection.CUSTOMER_COLLECTION, "12000")), "5000", "1200");
            assertThat(collected.netDueFromDriver()).isEqualByComparingTo("13800.00");

            Settlement deposited = settle(List.of(
                    mv(FleetCashDirection.ADVANCE_OUT, "8000"),
                    mv(FleetCashDirection.CUSTOMER_COLLECTION, "12000"),
                    mv(FleetCashDirection.COLLECTION_DEPOSIT, "12000"),
                    mv(FleetCashDirection.CASH_RETURN, "1800")), "5000", "1200");
            assertThat(deposited.netDueFromDriver()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("a deposit is reported apart from a cash return, so the bank line can be split")
        void depositIsNotAReturn() {
            // Both discharge the obligation, so a single direction would still balance — and still be
            // wrong. returned_total would read 13,800 against an advance of 8,000, showing a driver
            // returning more than he was ever given, with nothing to tell the customer's 12,000 from
            // his own unspent 1,800. The booking could then be receipted for the same 12,000 again.
            Settlement s = settle(List.of(
                    mv(FleetCashDirection.ADVANCE_OUT, "8000"),
                    mv(FleetCashDirection.CUSTOMER_COLLECTION, "12000"),
                    mv(FleetCashDirection.COLLECTION_DEPOSIT, "12000"),
                    mv(FleetCashDirection.CASH_RETURN, "1800")), "5000", "1200");

            assertThat(s.returnedTotal()).isEqualByComparingTo("1800.00");
            assertThat(s.depositedTotal()).isEqualByComparingTo("12000.00");
            assertThat(s.collectedTotal()).isEqualByComparingTo("12000.00");
        }
    }

    @Nested
    @DisplayName("recovery and adjustments")
    class RecoveryAndAdjustments {

        @Test
        @DisplayName("a challan charged to the driver INCREASES what he owes — it does not reduce the cost")
        void recoveryIncreasesObligation() {
            Settlement without = settle(List.of(mv(FleetCashDirection.ADVANCE_OUT, "8000")), "5000", "1200");
            Settlement with = settle(List.of(
                    mv(FleetCashDirection.ADVANCE_OUT, "8000"),
                    mv(FleetCashDirection.RECOVERY, "1000")), "5000", "1200");

            assertThat(without.netDueFromDriver()).isEqualByComparingTo("1800.00");
            assertThat(with.netDueFromDriver()).isEqualByComparingTo("2800.00");
            assertThat(with.adjustmentTotal()).isEqualByComparingTo("1000.00");
        }

        @Test
        @DisplayName("ADJUSTMENT_CREDIT writes off — the case a single +1 ADJUSTMENT constant got backwards")
        void creditWritesOff() {
            // The original enum had one ADJUSTMENT at +1 whose javadoc named "a written-off shortfall"
            // as its use case. Writing off Rs 47 would have posted +47 and left the driver owing 94.
            Settlement s = settle(List.of(
                    mv(FleetCashDirection.ADVANCE_OUT, "8000"),
                    mv(FleetCashDirection.CASH_RETURN, "1753"),
                    mv(FleetCashDirection.ADJUSTMENT_CREDIT, "47")), "5000", "1200");

            assertThat(s.netDueFromDriver()).isEqualByComparingTo("0.00");
            assertThat(s.adjustmentTotal()).isEqualByComparingTo("-47.00");
        }

        @Test
        @DisplayName("ADJUSTMENT_DEBIT and ADJUSTMENT_CREDIT cancel")
        void debitAndCreditCancel() {
            Settlement s = settle(List.of(
                    mv(FleetCashDirection.ADVANCE_OUT, "5000"),
                    mv(FleetCashDirection.ADJUSTMENT_DEBIT, "300"),
                    mv(FleetCashDirection.ADJUSTMENT_CREDIT, "300")), "5000", "0");

            assertThat(s.netDueFromDriver()).isEqualByComparingTo("0.00");
            assertThat(s.adjustmentTotal()).isEqualByComparingTo("0.00");
        }
    }

    @Nested
    @DisplayName("the company owing the driver")
    class CompanyOwes {

        @Test
        @DisplayName("spend beyond the advance leaves a negative net — he is owed money")
        void negativeNet() {
            Settlement s = settle(List.of(mv(FleetCashDirection.ADVANCE_OUT, "5000")), "6000", "1200");

            assertThat(s.netDueFromDriver()).isEqualByComparingTo("-2200.00");
            assertThat(s.isSquared()).isFalse();
        }

        @Test
        @DisplayName("no advance at all: the company owes his allowance")
        void allowanceOnly() {
            Settlement s = settle(List.of(), "0", "1200");
            assertThat(s.netDueFromDriver()).isEqualByComparingTo("-1200.00");
        }
    }

    @Nested
    @DisplayName("multi-driver trips are settled per driver, never netted")
    class MultiDriver {

        @Test
        @DisplayName("netting two drivers into one row produces a number that is right for neither")
        void nettingIsWrong() {
            // Six-day trip, handover on day three. Settled per driver — the shape the schema enforces
            // with a UNIQUE(trip_id, driver_id).
            Settlement a = settle(List.of(mv(FleetCashDirection.ADVANCE_OUT, "8000")), "3000", "1200");
            Settlement b = settle(List.of(mv(FleetCashDirection.ADVANCE_OUT, "5000")), "6000", "1200");

            assertThat(a.netDueFromDriver()).isEqualByComparingTo("3800.00");   // A owes 3,800
            assertThat(b.netDueFromDriver()).isEqualByComparingTo("-2200.00");  // B is owed 2,200

            // A single trip-level row computes 13,000 − 9,000 − 2,400 = 1,600.
            Settlement netted = settle(List.of(
                    mv(FleetCashDirection.ADVANCE_OUT, "8000"),
                    mv(FleetCashDirection.ADVANCE_OUT, "5000")), "9000", "2400");
            assertThat(netted.netDueFromDriver()).isEqualByComparingTo("1600.00");

            // And 1,600 IS the arithmetic sum of the two — the arithmetic is linear, which is exactly
            // what makes netting seductive. The defect is not that the total is wrong; it is that the
            // total is not a position ANYONE holds:
            assertThat(netted.netDueFromDriver())
                    .isEqualByComparingTo(a.netDueFromDriver().add(b.netDueFromDriver()));
            assertThat(netted.netDueFromDriver()).isNotEqualByComparingTo(a.netDueFromDriver());
            assertThat(netted.netDueFromDriver()).isNotEqualByComparingTo(b.netDueFromDriver());

            // So collecting 1,600 leaves 3,800 uncollected from A and 2,200 unpaid to B, and one
            // acknowledgement releases both men. A settlement must therefore be keyed on
            // (trip, driver) — UNIQUE(trip_id, driver_id) — and the trip is settled only when every
            // driver's own row is squared.
            assertThat(a.isSquared()).isFalse();
            assertThat(b.isSquared()).isFalse();
        }
    }

    @Nested
    @DisplayName("input guards")
    class Guards {

        @Test
        @DisplayName("a negative cash amount is rejected — direction carries the sign, not the amount")
        void rejectsNegativeAmount() {
            // A CASH_RETURN of -1800 would ADD 1800 to what the driver owes: a Rs 3,600 swing from
            // one minus sign, with nothing between the entry form and the balance.
            assertThatThrownBy(() -> settle(List.of(mv(FleetCashDirection.CASH_RETURN, "-1800")), "0", "0"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be positive");
        }

        @Test
        @DisplayName("zero is not a movement")
        void rejectsZeroAmount() {
            assertThatThrownBy(() -> settle(List.of(mv(FleetCashDirection.ADVANCE_OUT, "0")), "0", "0"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("negative spend or allowance is rejected")
        void rejectsNegativeInputs() {
            assertThatThrownBy(() -> settle(List.of(), "-100", "0"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Driver cash spend");
            assertThatThrownBy(() -> settle(List.of(), "0", "-100"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Allowance");
        }

        @Test
        @DisplayName("nulls are treated as zero, not as a crash")
        void nullsAreZero() {
            Settlement s = FleetSettlementCalculator.settle(null, null, null);
            assertThat(s.netDueFromDriver()).isEqualByComparingTo("0.00");
            assertThat(s.isSquared()).isTrue();
        }

        @Test
        @DisplayName("every direction is handled — a new constant must not silently do nothing")
        void everyDirectionIsAccountedFor() {
            for (FleetCashDirection d : FleetCashDirection.values()) {
                Settlement s = settle(List.of(mv(d, "100")), "0", "0");
                assertThat(s.netDueFromDriver())
                        .as("%s must move the balance by its own signum", d)
                        .isEqualByComparingTo(BigDecimal.valueOf(100L * d.signum()).setScale(2));
            }
        }
    }
}
