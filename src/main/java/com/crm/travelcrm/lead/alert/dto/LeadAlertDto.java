package com.crm.travelcrm.lead.alert.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * The new-lead alert: everything the toast, the incoming-leads row and the bell entry need, and
 * nothing else.
 *
 * <p><b>Deliberately not {@code LeadResponseDto}.</b> This object is broadcast to EVERY connected
 * user of the tenant, including agents whose row-scope would not normally let them see the lead
 * (the approved claim-window widening). Sending the full lead would put notes, itinerary, budget
 * breakdown and assistance details on that wire too. What ships here is what someone needs to
 * decide "do I take this call" — and it stops being broadcast the moment the lead locks.
 *
 * <p><b>Pax is sent structured, not pre-formatted.</b> "2 Adults, 1 Child" is a presentation
 * decision that varies by locale and by how much room the row has; a server-built string forces
 * every surface to display it identically or re-parse it.
 */
@Data
@Builder
public class LeadAlertDto {

    private UUID leadPublicId;
    private String leadCode;

    private String customerName;
    /** Needed for the Call and WhatsApp quick actions — the whole point of acting from the toast. */
    private String phone;

    /** Display name, e.g. "JustDial". What the badge shows. */
    private String source;
    /**
     * The enum constant, e.g. {@code JUSTDIAL}. The FE keys its source colour off THIS, never off
     * {@link #source} — display names are prose and change; enum names are the contract.
     */
    private String sourceKey;

    /** First itinerary stop, or the lead's own departure city when no itinerary was captured. */
    private String destination;

    private Integer adults;
    private Integer children;
    private Integer infants;

    /** The customer's stated budget. Null on an enquiry that has not named one yet. */
    private BigDecimal value;

    private UUID ownerPublicId;
    private String ownerName;

    /** What a claim must submit to win. Bumped by every ownership transition. */
    private int claimVersion;
    private boolean openToClaim;
    private String leadStage;

    private LocalDateTime createdAt;
    private Integer slaTargetSeconds;

    /**
     * Seconds left before this lead breaches, computed on the SERVER at broadcast time; negative
     * once breached, null once the clock has stopped.
     *
     * <p>Sent alongside {@link #createdAt} rather than instead of it because the client's clock is
     * not trustworthy — a browser several minutes off would show a countdown that disagrees with
     * the breach count on the same screen. The client ticks down locally from this value, so the
     * two always start in agreement.
     */
    private Long slaSecondsRemaining;

    // ── Ownership-change context (CLAIMED / LOCKED events only) ──────────────

    /** Who held the lead before this event. Null on a NEW_LEAD alert — nobody did. */
    private String previousOwnerName;

    /** Display name of whoever performed the act, for "Claimed by Rakesh" toasts. */
    private String actorName;
}
