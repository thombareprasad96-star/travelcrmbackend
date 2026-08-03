package com.crm.travelcrm.hotelmarketplace.booking.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * What a SuperAdmin supplies to confirm a request.
 *
 * <p>This release is ON_REQUEST throughout — there is no rate calendar and no allotment — so the
 * final amounts are entered here, after the operator has actually spoken to the hotel. That is the
 * design's deliberate first-release position (§19): a system that confirms automatically against
 * inventory nobody maintains sells rooms that do not exist.</p>
 */
@Data
public class ApproveMarketplaceBookingRequest {

    /** What the platform owes the supplier. SuperAdmin-only — never leaves the admin DTO. */
    @NotNull(message = "Supplier total is required")
    @DecimalMin(value = "0.0", message = "Supplier total cannot be negative")
    private BigDecimal supplierTotal;

    /**
     * What the tenant owes the platform. Must be ≥ supplierTotal, or the platform is confirming a
     * sale it loses money on without saying so; the service checks and the difference is recorded as
     * {@code platformEarning}.
     */
    @NotNull(message = "Tenant payable is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "Tenant payable must be greater than zero")
    private BigDecimal tenantPayable;

    @Size(max = 100)
    private String supplierConfirmationNumber;

    /** Shown to the tenant and frozen onto the booking — later policy edits must not rewrite it. */
    private String cancellationTermsSnapshot;

    private String internalNotes;
}
