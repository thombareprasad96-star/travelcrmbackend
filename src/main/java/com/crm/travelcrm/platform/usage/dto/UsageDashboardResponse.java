package com.crm.travelcrm.platform.usage.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/** Full usage dashboard payload: rollup header + per-tenant rows (worst-usage first). */
@Data
@Builder
public class UsageDashboardResponse {
    private UsageOverviewResponse overview;
    private List<TenantUsageResponse> tenants;
}