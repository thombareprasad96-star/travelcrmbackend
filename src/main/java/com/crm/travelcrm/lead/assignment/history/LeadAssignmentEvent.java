package com.crm.travelcrm.lead.assignment.history;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.lead.assignment.strategy.AssignmentStrategyType;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

/**
 * One row per ownership event in a lead's life: auto-assigned → claimed → claimed again →
 * contacted. This is the TIMELINE the lead detail screen renders.
 *
 * <p><b>Why this is not {@code LeadAssignmentAudit}.</b> That table answers a different question —
 * "what did the algorithm recommend, and did a human override it" — and holds exactly one row per
 * lead, written at creation. Folding the two together would either force every claim to invent a
 * recommendation it never had, or turn the audit's {@code manualOverride} flag into something that
 * means two different things depending on the row. They are kept apart, and both are written at
 * creation: the audit records the decision, this records the resulting ownership.
 *
 * <p>User references are logical FKs to {@code users.id} (house pattern, same as
 * {@code ActivityLog.actingUserId}); display names are denormalised snapshots taken at write time so
 * the timeline never joins back to {@code users} and still reads correctly after a rename or a
 * delete. External id is {@code publicId}; {@code createdAt} is the event timestamp.
 */
@Entity
@Table(name = "lead_assignment_events", indexes = {
        @Index(name = "idx_lead_assignment_event_tenant",  columnList = "tenant_id"),
        @Index(name = "idx_lead_assignment_event_lead",    columnList = "lead_id"),
        @Index(name = "idx_lead_assignment_event_created", columnList = "created_at"),
        @Index(name = "idx_lead_assignment_event_type",    columnList = "event_type")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class LeadAssignmentEvent extends BaseTenantEntity {

    /** Logical FK to {@code leads.id}. No DB-level FK — enforced at the application layer. */
    @Column(name = "lead_id", nullable = false)
    private Long leadId;

    /** The lead's public UUID — what the history endpoint keys on. */
    @Column(name = "lead_public_id", nullable = false)
    private UUID leadPublicId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 20)
    private LeadAssignmentEventType eventType;

    /** Previous owner (logical FK to {@code users.id}). Null on AUTO_ASSIGNED — there was none. */
    @Column(name = "from_user_id")
    private Long fromUserId;

    @Column(name = "from_user_name", length = 150)
    private String fromUserName;

    /**
     * New owner (logical FK to {@code users.id}). Null on CONTACTED and REOPENED — neither changes
     * ownership, so naming a "new owner" there would misreport the current one as having just moved.
     */
    @Column(name = "to_user_id")
    private Long toUserId;

    @Column(name = "to_user_name", length = 150)
    private String toUserName;

    /**
     * Who performed the act (logical FK to {@code users.id}). Null for machine-created leads — an
     * inbound webhook has no user behind it, and attributing one would be a lie in an audit trail.
     * {@link #actorName} carries the connection label or system reason in that case.
     */
    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "actor_name", length = 150)
    private String actorName;

    /** How the engine chose, on AUTO_ASSIGNED only. Null for every human act. */
    @Enumerated(EnumType.STRING)
    @Column(name = "strategy_used", length = 20)
    private AssignmentStrategyType strategyUsed;

    /** Short human-readable context ("claimed from the alert toast", "reopened: marked in error"). */
    @Column(name = "note", length = 255)
    private String note;
}
