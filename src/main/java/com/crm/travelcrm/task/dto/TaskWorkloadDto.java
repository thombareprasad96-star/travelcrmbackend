package com.crm.travelcrm.task.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

/**
 * One row of the team-workload view: a team member (or the "Unassigned" bucket, with a null
 * {@code assigneePublicId}) and their open/in-progress/done/overdue task counts, plus their count of
 * active (open-pipeline) leads — the same metric the load-based lead assignment balances on, so the
 * workload a manager sees matches how new leads are auto-distributed.
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
    /** Open + in-progress + done tasks (CANCELLED excluded from the workload view). */
    private long total;
    /** Active (open-pipeline) leads assigned to this user — excludes Converted/Lost. */
    private long activeLeads;
}