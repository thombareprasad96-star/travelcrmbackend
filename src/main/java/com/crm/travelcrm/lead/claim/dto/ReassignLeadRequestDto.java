package com.crm.travelcrm.lead.claim.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/**
 * Move a LOCKED lead to a different owner. Manager/admin only ({@code LEAD_REASSIGN_LOCKED}).
 *
 * <p>No {@code expectedClaimVersion}: a reassignment is a deliberate supervisory decision about a
 * lead that is no longer in the claim race, so there is no concurrent contender to lose to. The
 * conditional UPDATE still requires the lead to BE locked, which is the precondition that matters
 * here — an open lead must go through {@code /claim} so it cannot bypass the claim CAS.
 */
@Data
public class ReassignLeadRequestDto {

    /** publicId of the user to hand the lead to. Must be an assignable user of this tenant. */
    @NotNull(message = "assignedUserId is required")
    private UUID assignedUserId;

    /** Optional reason, surfaced in the assignment history ("agent on leave"). Truncated at 255. */
    private String note;
}
