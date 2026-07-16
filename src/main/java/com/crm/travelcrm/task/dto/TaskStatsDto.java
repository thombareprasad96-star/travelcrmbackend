package com.crm.travelcrm.task.dto;

import lombok.Builder;
import lombok.Getter;

/** Tenant-wide task counters for the board header / dashboard. */
@Getter
@Builder
public class TaskStatsDto {
    private long total;
    private long todo;
    private long inProgress;
    private long done;
    private long cancelled;
    private long overdue;
    private long dueToday;
}