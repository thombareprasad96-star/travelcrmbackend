package com.crm.travelcrm.lead.claim.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

/**
 * "I want this lead, and when I decided that it was at version N."
 *
 * <p>{@code expectedClaimVersion} is what makes the claim a compare-and-swap rather than a
 * last-writer-wins overwrite. It is REQUIRED on purpose: defaulting a missing value to 0 would let
 * any client that forgets the field win every race against clients that play fair, and defaulting it
 * to "whatever the row says" would remove the check entirely.
 */
@Data
public class ClaimLeadRequestDto {

    /**
     * The {@code claimVersion} the caller saw on the lead. The claim succeeds only if the row is
     * still at this version — i.e. nobody claimed, contacted or reassigned it in between.
     */
    @NotNull(message = "expectedClaimVersion is required")
    @PositiveOrZero(message = "expectedClaimVersion cannot be negative")
    private Integer expectedClaimVersion;

    /** Optional free text for the history trail ("from the alert toast"). Truncated at 255. */
    private String note;
}
