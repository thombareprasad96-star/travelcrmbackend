package com.crm.travelcrm.booking.dto.request;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Records one payment received against a booking.
 * Lives at {@code POST /api/bookings/{publicId}/payments}.
 */
@Getter
@Setter
public class CreateBookingPaymentRequest {

    @NotNull(message = "Payment amount is required")
    @DecimalMin(value = "0.01", message = "Payment amount must be greater than 0")
    @Digits(integer = 10, fraction = 2, message = "Invalid amount format")
    private BigDecimal amount;

    /** Free display label, e.g. "Advance" / "Partial" / "Balance". Optional. */
    @Size(max = 40, message = "Payment type is too long")
    private String paymentType;

    @Size(max = 40, message = "Payment method is too long")
    private String paymentMethod;

    /** Defaults to today in the service when omitted. */
    private LocalDate paymentDate;

    @Size(max = 120, message = "Reference cannot exceed 120 characters")
    private String reference;

    @Size(max = 500, message = "Notes cannot exceed 500 characters")
    private String notes;

    /** Optional — attribute this receipt to a single service line for per-service history. */
    private UUID serviceItemPublicId;
}