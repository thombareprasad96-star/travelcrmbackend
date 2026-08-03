package com.crm.travelcrm.hotelmarketplace.booking.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * What a SuperAdmin puts to the tenant when the hotel's price or terms moved after the request.
 *
 * <p>The reason is <b>required</b>, not optional. A repricing the tenant cannot explain to their own
 * customer is one they will refuse, and "the platform raised it" is not an explanation. This is the
 * one field that turns a number into a decision the tenant can actually make (design §8 Step 6B).</p>
 */
@Data
public class ReviseMarketplaceBookingRequest {

    /** Revised amount payable to the supplier. SuperAdmin-only; never echoed to the tenant. */
    @NotNull(message = "Revised supplier total is required")
    @DecimalMin(value = "0.0", message = "Revised supplier total cannot be negative")
    private BigDecimal revisedSupplierTotal;

    /** The number the tenant is being asked to accept. */
    @NotNull(message = "Revised tenant payable is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "Revised tenant payable must be greater than zero")
    private BigDecimal revisedTenantPayable;

    @NotBlank(message = "Tell the tenant why the price changed")
    private String reason;

    /** Replaces the frozen terms only if the tenant accepts. Null leaves the existing terms alone. */
    private String revisedCancellationTerms;

    /**
     * How long the tenant has to answer. Bounded rather than open-ended: an offer accepted three
     * weeks later is an offer the supplier has long since withdrawn, and the platform would be
     * committed to a price it can no longer buy at.
     */
    @DecimalMin(value = "1", message = "Give the tenant at least an hour to respond")
    private Integer validForHours;

    private String internalNotes;
}
