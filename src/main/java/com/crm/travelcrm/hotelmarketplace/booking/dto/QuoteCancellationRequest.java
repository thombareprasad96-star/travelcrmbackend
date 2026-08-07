package com.crm.travelcrm.hotelmarketplace.booking.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

/**
 * What the platform puts to a tenant who asked to cancel: this is what it will cost.
 *
 * <p>A quote, not a settlement. The tenant answers it, and only their acceptance ends the booking —
 * the same shape as {@link ReviseMarketplaceBookingRequest}, and for the same reason. A cancellation
 * charge moves the tenant's money exactly as a price increase does; deciding it for them would be
 * inconsistent with the care taken on the other side of the same booking (design §9 clauses 1-3).</p>
 */
@Data
public class QuoteCancellationRequest {

    /** What the hotel will retain. Zero for a free cancellation, which is a perfectly good quote. */
    @DecimalMin(value = "0.0", message = "Cancellation charge cannot be negative")
    private BigDecimal cancellationCharge;

    /**
     * How much of the platform's earning survives, if the tenant accepts. SuperAdmin-only — the
     * tenant sees the charge and their refund, never the split inside it.
     */
    @DecimalMin(value = "0.0", message = "Retained earning cannot be negative")
    private BigDecimal retainedPlatformEarning;

    /**
     * Why the charge is what it is. Required, and tenant-visible.
     *
     * <p>"₹4,000" with no explanation is a number a tenant cannot take to their own customer.
     * "Inside the hotel's 48-hour window; they are retaining one night" is one they can.</p>
     */
    @NotBlank(message = "Tell the tenant why the cancellation costs what it does")
    private String note;

    /** How long the quote stands. A hotel's goodwill on a waiver does not last indefinitely. */
    @DecimalMin(value = "1", message = "Give the tenant at least an hour to respond")
    private Integer validForHours;

    private String internalNotes;
}
