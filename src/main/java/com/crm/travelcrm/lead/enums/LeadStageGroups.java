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

    /** True when the stage is an open/active lead (anything that is not terminal). */
    public static boolean isActive(LeadStage stage) {
        return stage != null && !TERMINAL_STAGES.contains(stage);
    }
}