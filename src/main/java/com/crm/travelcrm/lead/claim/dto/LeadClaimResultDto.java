package com.crm.travelcrm.lead.claim.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The state of a lead's claim window after a successful claim / contact / reassign / reopen.
 *
 * <p>Deliberately NOT {@code LeadResponseDto}. This is returned to whoever performed the act, who
 * may be an OWN-scoped agent that just claimed a lead broadcast to the whole tenant — they now own
 * it, so the full record is theirs to see, but a mutation endpoint returning the entire lead (pax,
 * budget, itinerary, notes) is a wider blast radius than the act warrants. It also doubles as the
 * SSE broadcast payload in Phase 2, where a full lead body would be sent to every connected user.
 *
 * <p>{@code claimVersion} is echoed so the caller can immediately submit a follow-up action without
 * a re-read, and so the losing client can re-arm its button with a version that will actually match.
 */
@Data
@Builder
public class LeadClaimResultDto {

    private UUID leadPublicId;
    private String leadCode;
    private String customerName;

    /** Current owner after the act. */
    private UUID ownerPublicId;
    private String ownerName;

    /** The value a subsequent claim must submit. */
    private int claimVersion;

    /** True while the lead can still be claimed by anyone (not contacted, not terminal). */
    private boolean openToClaim;

    /** Null while open; set once first contact closed the window. */
    private LocalDateTime firstContactedAt;
    private UUID firstContactedByPublicId;
    private String firstContactedByName;

    /**
     * Measured first-response time. Survives a reopen — see {@code LeadRepository.reopenClaimWindow}
     * — so a lead that was contacted, reopened and contacted again still reports the original.
     */
    private Long firstResponseSeconds;

    /** The target this lead was created under, so the UI's countdown matches the server's judgement. */
    private Integer slaTargetSeconds;

    /** Whether the first-response target was missed, evaluated when this response was built. */
    private boolean slaBreached;

    /** The lead's stage after the act — the contact stamp moves it, so the caller must not guess. */
    private String leadStage;
}
