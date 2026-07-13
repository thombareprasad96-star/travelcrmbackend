package com.crm.travelcrm.booking.cancellation.enums;

/**
 * Progress of the refund a cancellation owes the customer. Kept in step (same transaction) with the
 * authoritative {@code Booking.refundedAmount} counter — this is the human-readable label, that is
 * the money of record.
 */
public enum RefundStatus {

    /** Nothing to refund (refundDue &lt;= 0 — the customer owed, or paid exactly the retention). */
    NOT_APPLICABLE,

    /** A refund is due but nothing has been disbursed yet. */
    PENDING,

    /** Some of the refund has been paid out, but not all. */
    PARTIALLY_PAID,

    /** The full refund due has been disbursed. */
    PAID
}