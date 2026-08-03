package com.crm.travelcrm.booking.dto.request;

import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Payload for {@code POST /api/leads/{publicId}/convert-to-booking}.
 *
 * <p>The lead is identified by the path {@code publicId}; the customer is resolved/created
 * server-side from the lead, so the client never sends a customerId. Everything here is
 * the reviewed, editable booking detail — prefilled on the frontend from the lead and the
 * accepted quotation, then confirmed by the agent before submit. GST / TCS / total payable /
 * net profit / payment status are all derived on the server and are never accepted here.</p>
 */
@Getter
@Setter
public class LeadConversionRequestDTO {

    /**
     * The accepted/selected quotation to carry over and link as the source (optional —
     * a lead with no quotation can still be converted manually). When present it must
     * belong to the same lead and tenant, else the request is rejected.
     */
    private UUID quotationPublicId;

    /**
     * publicId of the staff member who will service this booking. Optional — when omitted the
     * server assigns the LEAD's assignee (the person who nurtured the deal keeps it, rather than
     * whoever clicked Convert). The UI prefills the dropdown with that same default and sends it
     * back; sending anything else is an explicit, validated override.
     */
    private UUID assignedUserId;

    @NotBlank(message = "Customer name is required")
    @Size(max = 255, message = "Customer name too long")
    private String customerName;

    @NotBlank(message = "Customer phone is required")
    @Pattern(
            regexp = "^\\+?[0-9\\s()-]{7,20}$",
            message = "Invalid phone number"
    )
    @Size(max = 20, message = "Customer phone is too long")
    private String customerPhone;

    /**
     * Email as reviewed on the conversion form. Optional — a lead can reach conversion with only a
     * phone number.
     *
     * <p>The modal has always sent this field and the backend has always dropped it, so an email
     * collected or corrected at conversion time never reached the customer record. It is applied
     * fill-if-empty, never overwriting an address the customer master already holds.
     */
    @Email(message = "Enter a valid email address")
    @Size(max = 150, message = "Email must not exceed 150 characters")
    private String customerEmail;

    @NotBlank(message = "Destination is required")
    @Size(max = 255, message = "Destination too long")
    private String destination;

    /**
     * Optional — defaults to today on the server.
     */
    private LocalDate bookingDate;

    @NotNull(message = "Travel date is required")
    @FutureOrPresent(message = "Travel date cannot be in the past")
    private LocalDate travelDate;

    @NotNull(message = "Customer amount is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "Customer amount must be greater than 0")
    @Digits(integer = 10, fraction = 2, message = "Invalid amount format")
    private BigDecimal customerAmount;

    @NotNull(message = "Vendor cost is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "Vendor cost must be greater than 0")
    @Digits(integer = 10, fraction = 2, message = "Invalid amount format")
    private BigDecimal vendorCost;

    /**
     * Whether this is an overseas tour programme package. Drives TCS when the tenant's policy is
     * OVERSEAS_ONLY; ignored under NEVER / ALWAYS. Optional — absent means domestic.
     */
    private Boolean overseasTourPackage;

    /**
     * Advance collected at conversion time — optional, defaults to 0.
     */
    @DecimalMin(value = "0.0", message = "Paid amount cannot be negative")
    @Digits(integer = 10, fraction = 2, message = "Invalid amount format")
    private BigDecimal paidAmount = BigDecimal.ZERO;

    /**
     * Services included in the booking (e.g. Flight, Hotel, Sightseeing, Cruise, Vehicle).
     */
    private List<String> services = new ArrayList<>();
}