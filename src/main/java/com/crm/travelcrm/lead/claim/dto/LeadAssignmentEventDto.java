package com.crm.travelcrm.lead.claim.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One entry in a lead's ownership timeline, oldest first:
 * auto-assigned → claimed → claimed again → contacted.
 *
 * <p>Names are the snapshots stored on the event row, not live joins to {@code users} — the trail
 * must still read correctly after someone is renamed or removed, and a six-month-old entry saying
 * "assigned to [deleted user]" is worse than useless in an audit.
 */
@Data
@Builder
public class LeadAssignmentEventDto {

    private UUID id;

    /** AUTO_ASSIGNED | CLAIMED | REASSIGNED | CONTACTED | REOPENED. */
    private String eventType;

    private String fromUserName;
    private String toUserName;

    /** Who performed it. Null for machine-created leads; {@code actorName} then carries the source. */
    private String actorName;

    /** Populated on AUTO_ASSIGNED only — how the engine picked. Null for every human act. */
    private String strategyUsed;

    private String note;
    private LocalDateTime occurredAt;
}
