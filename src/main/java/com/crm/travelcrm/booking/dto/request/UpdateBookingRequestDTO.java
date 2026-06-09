// ── UpdateBookingRequestDTO ───────────────────────────────────────────────────
//
// All fields are OPTIONAL for a patch operation.
// Null = "don't change this field" (enforced by MapStruct IGNORE strategy)
// Only fields the client is permitted to change are present here.

package com.crm.travelcrm.booking.dto.request;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
public class UpdateBookingRequestDTO {

    // All fields nullable — partial update pattern
    // Validation only runs when the field is present

    private String destination;

    @FutureOrPresent(message = "Travel date cannot be in the past")
    private LocalDate travelDate;

    private LocalDate bookingDate;

    @DecimalMin(value = "0.0", inclusive = false,
            message = "Customer amount must be greater than 0")
    @Digits(integer = 10, fraction = 2, message = "Invalid amount format")
    private BigDecimal customerAmount;

    @DecimalMin(value = "0.0", inclusive = false,
            message = "Vendor cost must be greater than 0")
    @Digits(integer = 10, fraction = 2, message = "Invalid amount format")
    private BigDecimal vendorCost;

    private List<String> services;

    // ── Removed from your original ────────────────────────────────────────────
    // customerName  → resolved server-side, never client-supplied
    // customerId    → immutable after creation
    // paidAmount    → dedicated PaymentUpdateRequestDTO + endpoint only
    // bookingCode   → immutable
    // status        → dedicated StatusUpdateRequestDTO + endpoint only
}