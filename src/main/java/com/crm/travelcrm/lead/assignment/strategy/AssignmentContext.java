package com.crm.travelcrm.lead.assignment.strategy;

import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.Map;

/**
 * Immutable input a {@link LeadAssignmentStrategy} needs to compute a recommendation. Strategies are
 * <b>pure functions</b> over this context — they never touch the database or mutate state, so the
 * same context yields the same pick whether it is a read-only "peek" (the recommendation endpoint)
 * or the authoritative recompute at lead-creation time. All user references are internal {@code Long}
 * ids (the caller maps them back to publicIds for the API).
 */
@Getter
@Builder
public class AssignmentContext {

    /** Current tenant. */
    private final Long tenantId;

    /** Internal id of the user creating the lead — the pick for {@link AssignmentStrategyType#SELF}. */
    private final Long currentUserId;

    /**
     * Internal id of an explicitly requested assignee, or {@code null}. The pick for
     * {@link AssignmentStrategyType#MANUAL}.
     */
    private final Long requestedAssigneeUserId;

    /**
     * Eligible candidate user ids, <b>sorted ascending</b> for deterministic rotation. Never null;
     * may be empty (a strategy then recommends nothing).
     */
    @Builder.Default
    private final List<Long> candidateUserIds = List.of();

    /**
     * Workload score per candidate id — {@code UserWorkload.score()}, i.e.
     * {@code todo + inProgress + activeLeads + openReminders} — zero-filled for every candidate (so
     * an idle user, the most attractive target, is present with 0 rather than absent). Never null.
     *
     * <p>Was previously the active-lead count alone. It is deliberately NOT named for its components:
     * the strategy only needs "who is least busy", and {@code WorkloadService} owns what that means.
     */
    @Builder.Default
    private final Map<Long, Long> workloadScores = Map.of();

    /**
     * The last user id the round-robin cursor handed out for this tenant (persisted), or {@code null}
     * if the cursor has never advanced. Used to rotate to the next candidate.
     */
    private final Long lastAssignedUserId;
}