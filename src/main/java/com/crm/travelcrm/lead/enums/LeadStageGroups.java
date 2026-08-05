package com.crm.travelcrm.lead.enums;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Active-vs-terminal classification for {@link LeadStage}, kept OUT of the enum itself so the
 * enum stays a pure list of constants (no behaviour). This is the single home for "which stages
 * count as an open/active lead", shared by the duplicate-detection logic, the workload/assignment
 * feature, and any future consumer — so they can never drift.
 *
 * <p><b>Definition.</b> A lead is <i>active</i> while it is anything other than the two terminal
 * stages {@code CONVERTED} and {@code LOST}. This is the exact complement of {@link #TERMINAL_STAGES}
 * and mirrors the {@code uq_leads_*_open} partial unique indexes in {@code db/indexes.sql}
 * ({@code lead_stage NOT IN ('CONVERTED','LOST')}). Deriving {@code ACTIVE_STAGES} as the complement
 * (rather than hard-listing the open stages) means any stage added to the enum later defaults to
 * ACTIVE unless it is explicitly declared terminal — matching the DB's {@code NOT IN (...)} semantics.
 *
 * <p>{@code REOPENED} is ACTIVE: a booking that was converted then cancelled flips its lead back to
 * REOPENED, and it is a live lead again.
 */
public final class LeadStageGroups {

    private LeadStageGroups() {}

    /**
     * Terminal (closed) stages. A lead here no longer blocks a fresh lead for the same contact,
     * and {@code CONVERTED} specifically is owned by the booking lifecycle. Kept in sync with the
     * {@code uq_leads_*_open} partial unique indexes in {@code db/indexes.sql}.
     */
    public static final Set<LeadStage> TERMINAL_STAGES =
            Set.of(LeadStage.CONVERTED, LeadStage.LOST);

    /**
     * Active/open pipeline stages — the complement of {@link #TERMINAL_STAGES}
     * ({@code NEW_LEAD, CONTACTED, FOLLOW_UP, QUALIFIED, PROPOSAL_SENT, REOPENED}). Immutable.
     */
    public static final Set<LeadStage> ACTIVE_STAGES = Collections.unmodifiableSet(
            EnumSet.complementOf(EnumSet.of(LeadStage.CONVERTED, LeadStage.LOST)));

    /**
     * Stages that mean a human has actually engaged the customer — reaching any of them CLOSES the
     * claim window and starts the SLA measurement, whether it was reached by the "Mark Contacted"
     * button or by dragging the card on the Kanban board. Both doors must lead to the same place or
     * the drag silently bypasses the lock.
     *
     * <p>Deliberately hard-listed rather than derived as "not NEW_LEAD", because the two exclusions
     * are meaningful and a derived set would get them wrong:
     * <ul>
     *   <li>{@code LOST} is NOT engagement — a junk enquiry gets binned without anyone calling
     *       anybody, and counting that as a first response would flatter every SLA report.</li>
     *   <li>{@code REOPENED} is NOT engagement — it is the booking lifecycle handing a cancelled
     *       conversion back to the pipeline, not a conversation.</li>
     *   <li>{@code CONVERTED} is unreachable through the stage endpoints anyway (owned by the
     *       booking lifecycle) but is listed for completeness: a lead cannot become a booking
     *       without someone having spoken to the customer.</li>
     * </ul>
     */
    public static final Set<LeadStage> ENGAGED_STAGES = Collections.unmodifiableSet(EnumSet.of(
            LeadStage.CONTACTED,
            LeadStage.FOLLOW_UP,
            LeadStage.QUALIFIED,
            LeadStage.PROPOSAL_SENT,
            LeadStage.CONVERTED));

    /** True when the stage is an open/active lead (anything that is not terminal). */
    public static boolean isActive(LeadStage stage) {
        return stage != null && !TERMINAL_STAGES.contains(stage);
    }

    /** True when reaching this stage means first contact was made. See {@link #ENGAGED_STAGES}. */
    public static boolean isEngaged(LeadStage stage) {
        return stage != null && ENGAGED_STAGES.contains(stage);
    }
}