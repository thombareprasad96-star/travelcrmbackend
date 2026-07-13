package com.crm.travelcrm.booking.cancellation.dto;

import com.crm.travelcrm.booking.cancellation.enums.CancellationPolicyLevel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Create or supersede a cancellation policy. Posting for a (level, owner) that already has an active
 * version publishes a NEW immutable version (the prior one is deactivated), so this doubles as "edit".
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateCancellationPolicyRequest {

    @NotNull(message = "level is required (COMPANY or PACKAGE)")
    private CancellationPolicyLevel level;

    /** Required when level = PACKAGE (the owning package/quotation publicId); must be null for COMPANY. */
    private UUID ownerPublicId;

    @Size(max = 150, message = "name too long")
    private String name;

    /** Defaults to today when omitted. Governs point-in-time resolution of legacy bookings. */
    private LocalDate effectiveFrom;

    /** GST due on the retained cancellation charge? Defaults true. */
    private Boolean gstOnChargeApplicable;

    /** TCS on the un-availed portion refundable to the customer? Defaults true. */
    private Boolean tcsRefundable;

    @NotEmpty(message = "at least one cancellation band is required")
    @Valid
    private List<CancellationPolicyBandDto> bands;
}