package com.crm.travelcrm.report.geographic.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Stat-card counts for the Geographic Distribution header. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeoSummaryDTO {
    private long totalLeads;
    private long hotLeads;
    private long warmLeads;
    private long coldLeads;
    private long freshLeads;
    private long converted;
}