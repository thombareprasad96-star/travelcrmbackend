package com.crm.travelcrm.booking.service;

import com.crm.travelcrm.booking.enums.ExpensePaymentStatus;
import com.crm.travelcrm.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The single place that decides what a booking expense's money columns become. A pure function —
 * no Spring, no database, no clock — so every rule below is unit-testable in isolation
 * ({@code ExpenseSettlementCalculatorTest}), in the spirit of {@code CancellationCalculator}.
 *
 * <p><b>Why this exists at all.</b> The expense form computes {@code paidAmount} and
 * {@code outstandingAmount} in the browser and posts both. The rule this codebase already writes
 * into its own booking API client — <em>"the BACKEND computes every rupee … it must never
 * recompute a retained/refund amount in JS"</em> — applies here too, so the posted
 * {@code outstandingAmount} is discarded outright and {@code paidAmount} is re-derived from the
 * caller's stated intent. What the browser sends is treated as a request, never as a figure.
 *
 * <p><b>The rules.</b> {@code paymentStatus} is read as INTENT, and only for the two values that
 * are unambiguous about the money:
 * <ul>
 *   <li>{@code CREDIT} — "nothing paid" ⇒ paidAmount is forced to 0, whatever was posted.</li>
 *   <li>{@code PAID} — "settled in full" ⇒ paidAmount is forced to the full amount, so a stale
 *       figure in the form can never leave a rupee hanging as payable.</li>
 *   <li>{@code PARTIAL} (or absent) — the posted paidAmount is the intent and is kept as-is.</li>
 * </ul>
 * The status then STORED is derived back from that settled paidAmount, never taken from the
 * request. The two can therefore never disagree in the database: a row claiming PARTIAL with
 * nothing paid is not representable, because it comes back out as CREDIT.
 *
 * <p><b>What is rejected rather than adjusted.</b> {@code paidAmount > amount} is a genuine
 * contradiction — silently clamping it would quietly rewrite a figure someone typed and misstate
 * what was disbursed — so it is a 400. Everything else is normalised, because normalising is
 * lossless there.
 */
public final class ExpenseSettlementCalculator {

    /** Money is stored to the paise, matching {@code numeric(12,2)} on every amount column. */
    private static final int SCALE = 2;

    private ExpenseSettlementCalculator() {
    }

    /**
     * The settled money position of one expense line.
     *
     * @param paidAmount       what has actually been disbursed, scaled to 2dp, 0 ≤ paid ≤ amount
     * @param outstandingAmount amount − paidAmount, never negative
     * @param status           derived from the two above, never the caller's word
     */
    public record Settlement(BigDecimal paidAmount,
                             BigDecimal outstandingAmount,
                             ExpensePaymentStatus status) {
    }

    /**
     * @param amount           the line total; must be non-null and > 0 (bean validation already
     *                         enforces this on the request DTO — re-checked here so the calculator
     *                         is safe to call from anywhere, including a future import path)
     * @param requestedPaid    the paidAmount as posted; null is read as zero
     * @param requestedStatus  the caller's stated intent; null is read as {@code PARTIAL}, i.e.
     *                         "take the posted paidAmount at face value"
     */
    public static Settlement settle(BigDecimal amount,
                                    BigDecimal requestedPaid,
                                    ExpensePaymentStatus requestedStatus) {

        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Expense amount must be greater than 0.",
                    HttpStatus.BAD_REQUEST);
        }

        BigDecimal total = amount.setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal paid = switch (requestedStatus == null ? ExpensePaymentStatus.PARTIAL : requestedStatus) {
            case CREDIT -> BigDecimal.ZERO;
            case PAID   -> total;
            case PARTIAL -> requestedPaid == null ? BigDecimal.ZERO : requestedPaid;
        };
        paid = paid.setScale(SCALE, RoundingMode.HALF_UP);

        if (paid.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException("Paid amount cannot be negative.", HttpStatus.BAD_REQUEST);
        }
        if (paid.compareTo(total) > 0) {
            throw new BusinessException(
                    "Paid amount ₹" + paid.toPlainString()
                            + " cannot exceed the expense amount ₹" + total.toPlainString() + ".",
                    HttpStatus.BAD_REQUEST);
        }

        return new Settlement(paid, total.subtract(paid), deriveStatus(paid, total));
    }

    /**
     * The one mapping from money to status. Uses {@code compareTo}, never {@code equals} — the
     * latter is scale-sensitive, so {@code 1000} would not equal {@code 1000.00} and a fully
     * settled line could come back out as PARTIAL.
     */
    private static ExpensePaymentStatus deriveStatus(BigDecimal paid, BigDecimal total) {
        if (paid.compareTo(BigDecimal.ZERO) == 0) return ExpensePaymentStatus.CREDIT;
        if (paid.compareTo(total) >= 0)           return ExpensePaymentStatus.PAID;
        return ExpensePaymentStatus.PARTIAL;
    }
}
