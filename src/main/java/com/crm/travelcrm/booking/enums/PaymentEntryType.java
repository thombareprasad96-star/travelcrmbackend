package com.crm.travelcrm.booking.enums;

/**
 * Direction of a {@code booking_payments} ledger row.
 *
 * <p>{@code RECEIPT} is money IN (a customer payment) and sums into {@code Booking.paidAmount};
 * {@code REFUND} is money OUT (a disbursement back to the customer after a cancellation) and sums
 * into the separate, {@code @Version}-guarded {@code Booking.refundedAmount}. Keeping both directions
 * in one ledger gives a single chronological money trail, while the two counters never mix — so
 * {@code paidAmount}, {@code pendingAmount}, the payment status and every receipts aggregate are
 * unaffected by refunds.
 *
 * <p>Legacy rows created before this column existed are NULL and are treated as {@code RECEIPT}.
 */
public enum PaymentEntryType {
    RECEIPT,
    REFUND
}
