package com.crm.travelcrm.booking.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Body for {@code POST /api/bookings/{publicId}/cancellation/refund} — records ONE refund
 * disbursement against a cancelled booking. The backend caps the amount to what is still refundable
 * (the frozen {@code refundDue} minus what has already been paid out) and refuses anything above it;
 * the UI never computes the ceiling itself.
 */
@Getter
@Setter
public class RefundBookingRequestDTO {

    /** Amount actually disbursed now. Must be > 0 and ≤ the remaining refundable balance. */
    @NotNull(message = "Refund amount is required")
    @DecimalMin(value = "0.01", message = "Refund amount must be greater than zero")
    private BigDecimal amount;

    /** How it was paid back ("Bank Transfer", "UPI", "Original payment method", …). Free label. */
    @Size(max = 40)
    private String method;

    /** Transaction/UTR reference for the payout. */
    @Size(max = 120)
    private String reference;

    /** When the money left (defaults to today). */
    private LocalDate refundDate;

    @Size(max = 1000)
    private String notes;

    /**
     * Optional client token making a resubmit idempotent — the same key returns the original payout
     * instead of disbursing twice. The UI should send a fresh UUID per confirmed refund action.
     */
    @Size(max = 80)
    private String idempotencyKey;
}
