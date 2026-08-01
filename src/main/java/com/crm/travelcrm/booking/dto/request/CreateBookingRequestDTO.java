// ── CreateBookingRequestDTO ───────────────────────────────────────────────────

package com.crm.travelcrm.booking.dto.request;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

    /**
     * Whether this is an overseas tour programme package. Drives TCS when the tenant's policy is
     * OVERSEAS_ONLY; ignored under NEVER / ALWAYS. Optional — absent means domestic, which
     * under-collects rather than over-collects someone else's tax.
     *
     * <p>Per CBDT Circular 10/2023 a package qualifies only if it bundles at least TWO of
     * {international ticket, hotel, other similar expenditure} — a bare international air ticket is
     * not one — so this is an explicit judgement by the agent, never inferred from the destination.
     */
    private Boolean overseasTourPackage;

    // Initial payment at time of booking — optional, defaults to 0
    @DecimalMin(value = "0.0", message = "Paid amount cannot be negative")
    @Digits(integer = 10, fraction = 2, message = "Invalid amount format")
    private BigDecimal paidAmount = BigDecimal.ZERO;

    // Lead this booking was created from — optional
    private Long leadId;

    /**
     * publicId of the staff member who will service this booking. Optional — defaults to the
     * current user (there is no lead to inherit an assignee from on this path). Never the internal
     * Long id, and never trusted: the service validates it is a live user of this tenant who is
     * allowed to hold bookings.
     */
    private UUID assignedUserId;

    private List<String> services = new ArrayList<>();

    // ── Removed from your original ────────────────────────────────────────────
    // createdBy     → comes from JWT SecurityContext, never from client
    // customerName  → resolved server-side from customerId
}