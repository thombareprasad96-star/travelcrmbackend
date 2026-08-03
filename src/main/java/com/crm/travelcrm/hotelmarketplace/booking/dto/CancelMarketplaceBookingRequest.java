package com.crm.travelcrm.hotelmarketplace.booking.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

/**
 * What a SuperAdmin supplies to settle a cancellation.
 *
 * <p>The charge is <b>entered, not computed</b>. This release is ON_REQUEST throughout: there is no
 * rate calendar and no machine-readable cancellation policy, so what the hotel actually retained on
 * the day is something an operator learns by asking. A formula over today's data would produce a
 * confident number that no supplier agreed to (design §9 clause 2 — evaluate the snapshotted policy,
 * never today's master).</p>
 */
@Data
public class CancelMarketplaceBookingRequest {

    /**
     * What the supplier retained. Zero for a free cancellation. The tenant's refund is derived from
     * it server-side and is never posted by a client.
     */
    @DecimalMin(value = "0.0", message = "Cancellation charge cannot be negative")
    private BigDecimal cancellationCharge;

    /**
     * How much of the platform's earning survives the cancellation.
     *
     * <p>Defaults to zero — the platform earns on a stay, not on an order. It can never exceed the
     * charge actually collected: retaining commission out of a refund the tenant is owed would be
     * the platform paying itself with the tenant's money.</p>
     */
    @DecimalMin(value = "0.0", message = "Retained earning cannot be negative")
    private BigDecimal retainedPlatformEarning;

    @NotBlank(message = "Record why this booking was cancelled")
    private String reason;

    private String internalNotes;
}
