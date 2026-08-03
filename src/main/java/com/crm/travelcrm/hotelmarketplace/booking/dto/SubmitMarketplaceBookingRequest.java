package com.crm.travelcrm.hotelmarketplace.booking.dto;

import com.crm.travelcrm.customer.dto.request.CustomerResolveRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * "Send Hotel Booking Request" — the tenant's submit payload.
 *
 * <p>The action is a REQUEST, not a confirmation: nothing here can put the booking into a confirmed
 * state, and no price the client posts is trusted. The supplier-side money is computed and, in this
 * release, finalised by a SuperAdmin at approval time.</p>
 *
 * <h3>The CRM booking</h3>
 * A CRM booking is mandatory — the payable has nowhere else to live ({@code BookingExpense.bookingId}
 * is NOT NULL), the calendar and timeline both key on it, and the traveler portal reads it. But
 * mandatory costs the tenant nothing, because this is link-or-create: supply
 * {@link #crmBookingPublicId} to attach to an existing trip, or leave it null and supply
 * {@link #customer} and the two amounts, and one is minted in the same request.
 */
@Data
public class SubmitMarketplaceBookingRequest {

    @NotNull(message = "Hotel is required")
    private UUID hotelPublicId;

    private UUID roomPublicId;
    private UUID mealPlanPublicId;

    @NotNull(message = "Check-in date is required")
    @FutureOrPresent(message = "Check-in cannot be in the past")
    private LocalDate checkIn;

    /** Exclusive: 10 Aug → 12 Aug is two nights, 10th and 11th. */
    @NotNull(message = "Check-out date is required")
    private LocalDate checkOut;

    @Min(value = 1, message = "At least one room is required")
    private Integer rooms = 1;

    @Min(1) private Integer adults = 1;
    @Min(0) private Integer children = 0;

    @NotBlank(message = "Lead guest name is required")
    @Size(max = 150)
    private String leadGuestName;

    @Size(max = 30)  private String leadGuestPhone;
    @Email @Size(max = 150) private String leadGuestEmail;

    private String specialRequests;

    /**
     * Tenant-generated, unique per tenant. Repeating it returns the ORIGINAL request rather than
     * creating a second one — the only guard against a double-click minting two CRM bookings.
     */
    @NotBlank(message = "Idempotency key is required")
    @Size(max = 100)
    private String idempotencyKey;

    // ── The CRM booking: link, or create ────────────────────────────────────

    /** Attach to this existing booking. When null, the fields below mint one. */
    private UUID crmBookingPublicId;

    /** Required only on the create branch. */
    @Valid
    private CustomerResolveRequest customer;

    /** Required only on the create branch — free text, snapshotted on the booking. */
    @Size(max = 200)
    private String destination;

    /** The tenant's own price to their customer. Their number; the platform records it, never sets it. */
    @DecimalMin(value = "0.0", inclusive = false, message = "Customer amount must be greater than zero")
    private BigDecimal tenantCustomerSellingAmount;

    /** Optional source enquiry, when the trip came from a lead. */
    private UUID leadPublicId;
}
