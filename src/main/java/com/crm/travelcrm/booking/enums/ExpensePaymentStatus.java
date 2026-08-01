package com.crm.travelcrm.booking.enums;

/**
 * Settlement state of one {@code booking_expenses} row — how much of what the agency owes its
 * vendor for that line has actually been paid out.
 *
 * <p>This is the money-OUT mirror of {@link PaymentStatus} (which tracks money IN from the
 * customer) and is deliberately a separate enum: the customer-side vocabulary carries a
 * {@code REFUNDED} terminal state that has no meaning on a vendor bill, and the expense side
 * carries {@code CREDIT} — the "udhar" case where nothing has been paid yet and the whole amount
 * remains payable — which has no meaning on the customer side.
 *
 * <p><b>It is never stored from the client's word.</b> The value persisted is always derived from
 * the settled paid amount by {@code ExpenseSettlementCalculator.settle(...)}, so the status column
 * and the two money columns can never disagree:
 * {@code paidAmount == 0 → CREDIT}, {@code paidAmount >= amount → PAID}, otherwise
 * {@code PARTIAL}.
 */
public enum ExpensePaymentStatus {

    /** Nothing paid yet — the full amount is outstanding to the vendor. */
    CREDIT,

    /** Some paid, a balance remains. */
    PARTIAL,

    /** Settled in full — nothing outstanding. */
    PAID
}
