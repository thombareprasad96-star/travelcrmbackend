// ── PaymentUpdateRequestDTO ───────────────────────────────────────────────────
//
// Dedicated to a single concern: recording a payment against a booking.
// Lives at: PATCH /api/v1/bookings/{publicId}/payment

package com.crm.travelcrm.booking.dto.request;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
public class PaymentUpdateRequestDTO {

    // Amount being paid NOW — added to existing paidAmount in service
    // NOT the new total — to avoid race conditions and confusion
    @NotNull(message = "Payment amount is required")
    @DecimalMin(value = "0.01", message = "Payment amount must be greater than 0")
    @Digits(integer = 10, fraction = 2, message = "Invalid amount format")
    private BigDecimal amount;

    // Optional: date the payment was received (defaults to today)
    private LocalDate paymentDate;

    // Optional: reference number (bank transfer ref, cheque number, etc.)
    @Size(max = 100, message = "Reference cannot exceed 100 characters")
    private String paymentReference;

    // Optional: notes for this payment
    @Size(max = 500, message = "Notes cannot exceed 500 characters")
    private String notes;
}