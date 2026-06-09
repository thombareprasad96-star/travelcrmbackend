// ── CreateBookingRequestDTO ───────────────────────────────────────────────────

package com.crm.travelcrm.booking.dto.request;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class CreateBookingRequestDTO {

    // Customer resolved via FK — service fetches name for snapshot
    @NotNull(message = "Customer is required")
    private Long customerId;

    @NotBlank(message = "Customer name is required")        // ✅ added
    @Size(max = 255, message = "Customer name too long")    // ✅ added
    private String customerName;

    // Service resolves this to destination_id and stores snapshot
    @NotBlank(message = "Destination is required")
    private String destination;

    // Booking date is optional — defaults to today in service
    private LocalDate bookingDate;

    @NotNull(message = "Travel date is required")
    @FutureOrPresent(message = "Travel date cannot be in the past")
    private LocalDate travelDate;

    @NotNull(message = "Customer amount is required")
    @DecimalMin(value = "0.0", inclusive = false,
            message = "Customer amount must be greater than 0")
    @Digits(integer = 10, fraction = 2, message = "Invalid amount format")
    private BigDecimal customerAmount;

    @NotNull(message = "Vendor cost is required")
    @DecimalMin(value = "0.0", inclusive = false,
            message = "Vendor cost must be greater than 0")
    @Digits(integer = 10, fraction = 2, message = "Invalid amount format")
    private BigDecimal vendorCost;

    // Initial payment at time of booking — optional, defaults to 0
    @DecimalMin(value = "0.0", message = "Paid amount cannot be negative")
    @Digits(integer = 10, fraction = 2, message = "Invalid amount format")
    private BigDecimal paidAmount = BigDecimal.ZERO;

    // Lead this booking was created from — optional
    private Long leadId;

    private List<String> services = new ArrayList<>();

    // ── Removed from your original ────────────────────────────────────────────
    // createdBy     → comes from JWT SecurityContext, never from client
    // customerName  → resolved server-side from customerId
}