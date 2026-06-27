package com.crm.travelcrm.report.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** Reports-dashboard stat cards. {@code totalReports} is the fixed count of report types. */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportSummaryDTO {
    private long       totalReports;
    private long       activeUsers;
    private long       thisMonthLeads;
    private BigDecimal revenueTracked;
    private String     period;
    private String     generatedAt;
}
