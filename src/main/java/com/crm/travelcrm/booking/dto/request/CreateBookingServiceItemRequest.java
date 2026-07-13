package com.crm.travelcrm.booking.dto.request;

import com.crm.travelcrm.booking.enums.ServiceItemStatus;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Adds one service line to a booking.
 * Lives at {@code POST /api/bookings/{publicId}/services}.
 */
@Getter
@Setter
public class CreateBookingServiceItemRequest {

    @Size(max = 60, message = "Service type is too long")
    private String serviceType;

    @NotBlank(message = "Service title is required")
    @Size(max = 200, message = "Title is too long")
    private String title;

    private String description;

    private LocalDate serviceDate;
    private LocalDate endDate;

    /** Defaults to PENDING in the service when omitted. */
    private ServiceItemStatus status;

    @DecimalMin(value = "0.0", message = "Cost cannot be negative")
    @Digits(integer = 10, fraction = 2, message = "Invalid amount format")
    private BigDecimal cost;

    @DecimalMin(value = "0.0", message = "Vendor cost cannot be negative")
    @Digits(integer = 10, fraction = 2, message = "Invalid amount format")
    private BigDecimal vendorCost;

    @Size(max = 100, message = "Confirmation number is too long")
    private String confirmationNumber;

    private String notes;
}