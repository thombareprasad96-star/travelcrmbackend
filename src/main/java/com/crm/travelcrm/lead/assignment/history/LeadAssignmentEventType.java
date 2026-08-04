package com.crm.travelcrm.lead.assignment.history;

/**
 * What happened to a lead's ownership. One constant per row in {@code lead_assignment_events}.
 *
 * <p>Deliberately separate from {@code AssignmentStrategyType}: that says <em>how the algorithm
 * chose</em>, this says <em>what act occurred</em>. A CLAIMED row has no strategy — a human simply
 * took it — and an AUTO_ASSIGNED row's strategy is carried alongside, not instead.
 *
 * <p><b>Never remove a constant.</b> {@code @Enumerated(STRING)} maps the stored string back, so
 * deleting one breaks reading every historical row that carries it. Adding one requires refreshing
 * the {@code lead_assignment_events_event_type_check} block in {@code db/indexes.sql} — Hibernate
 * generates that constraint when it first creates the table and {@code ddl-auto=update} never alters
 * it, so a new constant is rejected at the DB until the block is refreshed.
 */
public enum LeadAssignmentEventType {

    /** The lead's first owner, chosen by the assignment engine at creation. Always the first row. */
    AUTO_ASSIGNED,

    /** A user took an open lead from its current owner. Only possible while the claim window is open. */
    CLAIMED,

    /** A manager/admin moved a LOCKED lead to a different owner (post-contact reassignment). */
    REASSIGNED,

    /** First contact made — the claim window closed and the owner became fixed. */
    CONTACTED,

    /**
     * A manager/admin reopened the claim window on a locked lead (undoing a mis-click). The lead
     * becomes claimable again; {@code firstResponseSeconds} stays frozen at its original value, so
     * this can never be used to erase a missed SLA.
     */
    REOPENED
}
