package com.crm.travelcrm.platform.usage.dto;

import lombok.Builder;
import lombok.Data;

/** Header rollups for the SuperAdmin usage dashboard. */
@Data
@Builder
public class UsageOverviewResponse {
    private long totalTenants;
    private long tenantsOverLimit;
    private long tenantsNearLimit;
    private long totalActiveUsers;
    private long totalBookingsThisMonth;
    private long totalStorageBytes;
    /** The near-limit warning threshold (percent of quota) applied to the rows. */
    private int warnThresholdPercent;
}