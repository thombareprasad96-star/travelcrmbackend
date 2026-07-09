package com.crm.travelcrm.platform.analytics.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** Platform analytics overview — everything the console dashboard renders in one payload. */
@Data
@Builder
public class AnalyticsOverviewResponse {

    // KPI totals
    private long totalTenants;
    private long activeTenants;
    private long trialTenants;
    private long suspendedTenants;
    private long expiredTenants;
    private long totalUsers;

    // Revenue KPIs (platform currency; see `currency`)
    private BigDecimal mrr;               // Σ plan price of operational (ACTIVE+TRIAL) tenants
    private BigDecimal outstanding;       // Σ UNPAID invoices
    private BigDecimal collectedThisMonth; // Σ PAID invoices issued this month
    private String currency;

    // Breakdowns
    private List<LabelValue> tenantsByStatus;
    private List<LabelValue> tenantsByPlan;

    // 12-month time-series (oldest → newest)
    private List<LabelValue> newTenantsByMonth;
    private List<MonthAmount> revenueByMonth;
}