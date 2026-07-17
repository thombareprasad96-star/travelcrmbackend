package com.crm.travelcrm.task.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

/**
 * One row of the team-workload view: a team member (or the "Unassigned" bucket, with a null
 * {@code assigneePublicId}) and their task counts, active leads and open reminders.
 *
 * <p>{@link #workloadScore} is the number this view is ORDERED by and the number the load-based lead
 * assignment balances on — the same {@code UserWorkload.score()} on both sides, so what a manager
 * sees explains how new leads are auto-distributed.
 */
@Getter
@Builder
public class TaskWorkloadDto {
    /** Null for the aggregate "Unassigned" bucket. */
    private UUID assigneePublicId;
    private String assigneeName;
    private long todo;
    private long inProgress;
    private long done;
    private long overdue;
    /**
     * Open + in-progress + done tasks (CANCELLED excluded from the workload view).
     *
     * <p>Reported, but deliberately NOT part of {@link #workloadScore} — it counts DONE, so ordering
     * by it made finishing work look like being busy.
     */
    private long total;
    /** Active (open-pipeline) leads assigned to this user — excludes Converted/Lost. */
    private long activeLeads;
    /** Reminders that are Active or OVERDUE. Snoozed/Completed/Dismissed are not open work. */
    private long openReminders;
    /**
     * {@code todo + inProgress + activeLeads + openReminders} — the shared workload metric
     * ({@code UserWorkload.score()}). This list is sorted by it, descending, and the load-based lead
     * assignment picks the MINIMUM of it.
     */
    private long workloadScore;
}