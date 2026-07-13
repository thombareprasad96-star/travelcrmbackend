package com.crm.travelcrm.booking.dto.request;

import com.crm.travelcrm.booking.enums.CancelAction;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Body for {@code POST /api/bookings/{publicId}/cancel}. {@code action} decides what happens to the
 * associated lead; the booking is always retained (status CANCELLED) with its immutable financial
 * cancellation record.
 *
 * <p>The charge is computed by the backend from the booking's pinned policy. The optional
 * {@code overrideChargeBase} / {@code overrideReason} let a user with {@code BOOKING_REFUND} override
 * or waive that computed charge; the override is settled here, at cancel time, and then frozen — the
 * Credit/Debit Note is issued with the final figure and never edited afterwards.
 */
@Getter
@Setter
public class CancelBookingRequestDTO {

    @NotNull(message = "Cancel action is required (MOVE_TO_LEAD or PERMANENT_DELETE_LEAD)")
    private CancelAction action;

    /** Free-text reason for the cancellation (recorded on the immutable cancellation record). */
    @Size(max = 1000)
    private String reason;

    /**
     * Optional manual retained base (pre-tax), overriding the policy-computed charge. A waiver is
     * {@code 0}. Requires {@code BOOKING_REFUND}. Clamped to [0, base fare] by the calculator.
     */
    @DecimalMin(value = "0.0", message = "overrideChargeBase cannot be negative")
    private BigDecimal overrideChargeBase;

    @Size(max = 1000)
    private String overrideReason;

    /**
     * How much of the booking's vendor cost the agency expects to recover (e.g. a hotel refund).
     * Affects only the internal revised-profit figure, never the customer's refund. Defaults to 0
     * (vendor cost treated as fully sunk).
     */
    @DecimalMin(value = "0.0", message = "vendorRecoverable cannot be negative")
    private BigDecimal vendorRecoverable;
}