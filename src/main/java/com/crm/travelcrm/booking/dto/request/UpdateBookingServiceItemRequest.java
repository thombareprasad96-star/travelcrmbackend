package com.crm.travelcrm.booking.dto.request;

import com.crm.travelcrm.booking.enums.ServiceItemStatus;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Partial update of a service line. All fields optional — null means "leave unchanged".
 * Lives at {@code PUT /api/bookings/{publicId}/services/{serviceItemPublicId}}.
 */
@Getter
@Setter
public class UpdateBookingServiceItemRequest {

    @Size(max = 60, message = "Service type is too long")
    private String serviceType;

    @Size(max = 200, message = "Title is too long")
    private String title;

    private String description;

    private LocalDate serviceDate;
    private LocalDate endDate;

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