package com.crm.travelcrm.booking.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One entry of the booking activity timeline — a read-only projection composed from records that
 * already carry who/when facts: Envers revisions of the booking (status changes), the payment and
 * expense ledgers (including soft-deleted rows, which become "removed" events), the cancellation
 * record and issued tax invoices. Nothing is persisted for the timeline itself; the underlying
 * records remain the single source of truth.
 *
 * <p>Deliberately carries no vendor-cost, profit or other calculated internal figures. Expense
 * events are also omitted by the timeline service unless the caller has
 * {@code BOOKING_PROFIT_READ}.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookingTimelineEventDTO {

    /**
     * Machine-readable event kind. One of: BOOKING_CREATED, STATUS_CHANGED,
     * PAYMENT_STATUS_CHANGED, PAYMENT_RECORDED, REFUND_RECORDED, PAYMENT_REMOVED,
     * EXPENSE_RECORDED, EXPENSE_REMOVED, BOOKING_CANCELLED, INVOICE_ISSUED, INVOICE_CANCELLED.
     */
    private String type;

    private String title;

    /** Optional human detail line ("Pending → Confirmed", method · reference, cancel reason). */
    private String detail;

    /** Who performed it — the audit createdBy/updatedBy/deletedBy snapshot; null when unknown. */
    private String actor;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime occurredAt;

    /** Money moved by this event (ledger amount), when applicable. */
    private BigDecimal amount;

    /** Document/transaction reference (invoice number, payment reference), when applicable. */
    private String reference;
}
