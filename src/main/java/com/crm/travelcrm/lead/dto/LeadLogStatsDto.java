package com.crm.travelcrm.lead.dto;

import lombok.Builder;
import lombok.Data;

/** Roll-up totals for the All-Lead-Logs hero stat cards. */
@Data
@Builder
public class LeadLogStatsDto {
    private long totalLogs;     // total log entries visible to the caller
    private long totalLeads;    // distinct leads that have at least one log
    private String today;       // "MMM dd, yyyy"
    private int totalPages;     // pre-computed at perPage = 12
}