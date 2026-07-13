package com.crm.travelcrm.booking.cancellation.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The frozen post-cancellation summary a booking carries — the authoritative refund position the
 * refund UI needs (how much is due, paid out and still refundable), read straight from the immutable
 * {@code BookingCancellation} record. All money is the backend's; the UI only displays it.
 */
@Getter
@Builder
public class CancellationSummaryDTO {

    private UUID       bookingPublicId;
    private String     bookingCode;

    private LocalDate  cancelledOn;
    private String     reason;

    /** True → the customer owes (a debit note was issued); there is nothing to refund. */
    private boolean    customerOwes;
    private BigDecimal customerBalanceOwed;

    /** Frozen amount owed back to the customer, and progress against it. */
    private BigDecimal refundDue;
    private BigDecimal totalRefunded;
    private BigDecimal remainingRefundable;

    /** NOT_APPLICABLE | PENDING | PARTIALLY_PAID | PAID. */
    private String     refundStatus;
    private boolean    fullyRefunded;

    private String     creditNoteNumber;
}