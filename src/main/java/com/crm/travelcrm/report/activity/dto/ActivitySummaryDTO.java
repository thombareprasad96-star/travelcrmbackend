package com.crm.travelcrm.report.activity.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Stat-card totals for the Activity Reports header. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActivitySummaryDTO {
    private long totalActivities;
    private long totalLogins;
    private long adminActions;
    private long uniqueUsers;
}