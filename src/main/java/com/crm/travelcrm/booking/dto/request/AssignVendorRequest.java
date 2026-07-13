package com.crm.travelcrm.booking.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Assigns (or re-assigns) a vendor to a booking service line.
 * Lives at {@code PUT /api/bookings/{publicId}/services/{serviceItemPublicId}/vendor}.
 * Pass a null/empty {@code vendorPublicId} is rejected; to unassign, delete the line or
 * PATCH it — assignment always points at a real vendor.
 */
@Getter
@Setter
public class AssignVendorRequest {

    @NotNull(message = "Vendor is required")
    private UUID vendorPublicId;

    /** Optional — record the agreed vendor cost for this line at assignment time. */
    private BigDecimal vendorCost;

    /** Optional — vendor confirmation / PNR captured at assignment time. */
    private String confirmationNumber;
}