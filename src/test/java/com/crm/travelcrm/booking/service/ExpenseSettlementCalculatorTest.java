package com.crm.travelcrm.booking.service;

import com.crm.travelcrm.booking.enums.ExpensePaymentStatus;
import com.crm.travelcrm.booking.service.ExpenseSettlementCalculator.Settlement;
import com.crm.travelcrm.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure-math tests for {@link ExpenseSettlementCalculator}. No Spring context — the calculator is a
 * pure function, so these run in milliseconds, in the spirit of {@code CancellationCalculatorTest}.
 *
 * <p>What is really under test is the promise the expense API makes: whatever arithmetic the
 * browser did, the figures that reach the database are the server's, and the status column can
 * never contradict the two money columns beside it.
 */
class ExpenseSettlementCalculatorTest {

    private static BigDecimal rupees(String amount) {
        return new BigDecimal(amount);
    }

    @Nested
    @DisplayName("the posted status is read as intent, not as a figure")
    class StatusIsIntent {

        @Test
        @DisplayName("CREDIT zeroes a paid amount the client sent anyway")
        void creditForcesZero() {
            Settlement settlement = ExpenseSettlementCalculator.settle(
                    rupees("5000.00"), rupees("2000.00"), ExpensePaymentStatus.CREDIT);

            assertThat(settlement.paidAmount()).isEqualByComparingTo("0.00");
            assertThat(settlement.outstandingAmount()).isEqualByComparingTo("5000.00");
            assertThat(settlement.status()).isEqualTo(ExpensePaymentStatus.CREDIT);
        }

        @Test
        @DisplayName("PAID fills the paid amount to the total, so a stale form leaves no phantom balance")
        void paidForcesFullAmount() {
            // The form seeds paidAmount from the amount when PAID is chosen, then the user edits the
            // amount again — a browser that missed the second edit would post 5000 against 7500 and
            // leave ₹2500 showing as payable to a vendor who was paid in full.
            Settlement settlement = ExpenseSettlementCalculator.settle(
                    rupees("7500.00"), rupees("5000.00"), ExpensePaymentStatus.PAID);

            assertThat(settlement.paidAmount()).isEqualByComparingTo("7500.00");
            assertThat(settlement.outstandingAmount()).isEqualByComparingTo("0.00");
            assertThat(settlement.status()).isEqualTo(ExpensePaymentStatus.PAID);
        }

        @Test
        @DisplayName("PARTIAL takes the posted paid amount at face value")
        void partialKeepsPostedAmount() {
            Settlement settlement = ExpenseSettlementCalculator.settle(
                    rupees("5000.00"), rupees("2000.00"), ExpensePaymentStatus.PARTIAL);

            assertThat(settlement.paidAmount()).isEqualByComparingTo("2000.00");
            assertThat(settlement.outstandingAmount()).isEqualByComparingTo("3000.00");
            assertThat(settlement.status()).isEqualTo(ExpensePaymentStatus.PARTIAL);
        }

        @Test
        @DisplayName("an absent status is read as PARTIAL — the posted amount stands")
        void nullStatusFallsBackToPartial() {
            Settlement settlement = ExpenseSettlementCalculator.settle(
                    rupees("5000.00"), rupees("1500.00"), null);

            assertThat(settlement.paidAmount()).isEqualByComparingTo("1500.00");
            assertThat(settlement.status()).isEqualTo(ExpensePaymentStatus.PARTIAL);
        }
    }

    @Nested
    @DisplayName("the stored status is derived back from the settled money")
    class StatusIsDerived {

        @Test
        @DisplayName("PARTIAL with nothing paid comes back out as CREDIT")
        void partialWithZeroPaidBecomesCredit() {
            // A row claiming PARTIAL while nothing has been paid is not representable in the table.
            Settlement settlement = ExpenseSettlementCalculator.settle(
                    rupees("5000.00"), BigDecimal.ZERO, ExpensePaymentStatus.PARTIAL);

            assertThat(settlement.status()).isEqualTo(ExpensePaymentStatus.CREDIT);
            assertThat(settlement.outstandingAmount()).isEqualByComparingTo("5000.00");
        }

        @Test
        @DisplayName("PARTIAL paid in full comes back out as PAID")
        void partialPaidInFullBecomesPaid() {
            Settlement settlement = ExpenseSettlementCalculator.settle(
                    rupees("5000.00"), rupees("5000.00"), ExpensePaymentStatus.PARTIAL);

            assertThat(settlement.status()).isEqualTo(ExpensePaymentStatus.PAID);
            assertThat(settlement.outstandingAmount()).isEqualByComparingTo("0.00");
        }

        @Test
        @DisplayName("a null paid amount is read as zero, not as an error")
        void nullPaidIsZero() {
            Settlement settlement = ExpenseSettlementCalculator.settle(
                    rupees("5000.00"), null, ExpensePaymentStatus.PARTIAL);

            assertThat(settlement.paidAmount()).isEqualByComparingTo("0.00");
            assertThat(settlement.status()).isEqualTo(ExpensePaymentStatus.CREDIT);
        }

        @Test
        @DisplayName("differing scales still settle — 1000 pays off 1000.00")
        void scaleDoesNotDefeatTheComparison() {
            // compareTo, not equals: BigDecimal("1000").equals(BigDecimal("1000.00")) is false, and
            // an equals-based check here would file a fully paid line as PARTIAL with ₹0 owing.
            Settlement settlement = ExpenseSettlementCalculator.settle(
                    rupees("1000.00"), rupees("1000"), ExpensePaymentStatus.PARTIAL);

            assertThat(settlement.status()).isEqualTo(ExpensePaymentStatus.PAID);
            assertThat(settlement.outstandingAmount()).isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("money is normalised to paise, and contradictions are refused")
    class MoneyIntegrity {

        @Test
        @DisplayName("both figures are stored at 2dp, matching numeric(12,2)")
        void amountsAreScaledToTwoDecimals() {
            Settlement settlement = ExpenseSettlementCalculator.settle(
                    rupees("100.005"), rupees("50.004"), ExpensePaymentStatus.PARTIAL);

            assertThat(settlement.paidAmount().scale()).isEqualTo(2);
            assertThat(settlement.paidAmount()).isEqualByComparingTo("50.00");
            assertThat(settlement.outstandingAmount()).isEqualByComparingTo("50.01");
        }

        @Test
        @DisplayName("paying more than the expense is rejected, never clamped")
        void overpaymentIsRejected() {
            // Clamping would quietly rewrite a figure someone typed and misstate what was disbursed.
            assertThatThrownBy(() -> ExpenseSettlementCalculator.settle(
                    rupees("5000.00"), rupees("6000.00"), ExpensePaymentStatus.PARTIAL))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("cannot exceed the expense amount")
                    .extracting(e -> ((BusinessException) e).getStatus())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("a negative paid amount is rejected")
        void negativePaidIsRejected() {
            assertThatThrownBy(() -> ExpenseSettlementCalculator.settle(
                    rupees("5000.00"), rupees("-1.00"), ExpensePaymentStatus.PARTIAL))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("cannot be negative");
        }

        @Test
        @DisplayName("a zero or null amount is rejected — there is no expense to settle")
        void nonPositiveAmountIsRejected() {
            assertThatThrownBy(() -> ExpenseSettlementCalculator.settle(
                    BigDecimal.ZERO, BigDecimal.ZERO, ExpensePaymentStatus.CREDIT))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("must be greater than 0");

            assertThatThrownBy(() -> ExpenseSettlementCalculator.settle(
                    null, BigDecimal.ZERO, ExpensePaymentStatus.CREDIT))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("must be greater than 0");
        }

        @Test
        @DisplayName("the outstanding balance always closes: paid + outstanding == amount")
        void theLedgerBalances() {
            for (String paid : new String[] {"0", "0.01", "1234.56", "4999.99", "5000.00"}) {
                Settlement settlement = ExpenseSettlementCalculator.settle(
                        rupees("5000.00"), rupees(paid), ExpensePaymentStatus.PARTIAL);

                assertThat(settlement.paidAmount().add(settlement.outstandingAmount()))
                        .as("paid %s + outstanding must equal the expense amount", paid)
                        .isEqualByComparingTo("5000.00");
                assertThat(settlement.outstandingAmount()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
            }
        }
    }
}
