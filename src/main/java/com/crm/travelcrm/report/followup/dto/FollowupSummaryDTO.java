package com.crm.travelcrm.report.followup.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** The six stat-card counts on the Follow-up Report header. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FollowupSummaryDTO {
    private long totalFollowups;
    private long overdue;
    private long dueToday;
    private long urgent;         // due within 3 days
    private long upcoming;
    private long highPriority;
}