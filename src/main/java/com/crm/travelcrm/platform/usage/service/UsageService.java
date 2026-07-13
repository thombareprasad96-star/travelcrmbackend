package com.crm.travelcrm.platform.usage.service;

import com.crm.travelcrm.platform.usage.dto.TenantUsageResponse;
import com.crm.travelcrm.platform.usage.dto.UpdateTenantQuotaRequest;
import com.crm.travelcrm.platform.usage.dto.UsageDashboardResponse;

import java.util.UUID;

public interface UsageService {

    /** Every live tenant's usage vs quota + rollup header, worst-usage first. */
    UsageDashboardResponse dashboard();

    /** A single tenant's usage vs quota. */
    TenantUsageResponse getUsage(UUID tenantPublicId);

    /** Override (or clear) a tenant's limits; returns the tenant's refreshed usage row. */
    TenantUsageResponse overrideQuota(UUID tenantPublicId, UpdateTenantQuotaRequest request);
}