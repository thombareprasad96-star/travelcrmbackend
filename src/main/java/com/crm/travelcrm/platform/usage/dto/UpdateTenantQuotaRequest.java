package com.crm.travelcrm.platform.usage.dto;

import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * SuperAdmin override of a tenant's usage limits. A non-null field sets that limit and pins the
 * override (so a later plan-change won't overwrite it); a null field is left unchanged. Set
 * {@code clearOverride=true} to unpin and revert every limit to the tenant's current plan defaults.
 */
@Data
public class UpdateTenantQuotaRequest {

    @Min(value = 1, message = "Max users must be at least 1")
    private Integer maxUsers;

    @Min(value = 1, message = "Max leads must be at least 1")
    private Integer maxLeads;

    @Min(value = 1, message = "Max bookings per month must be at least 1")
    private Integer maxBookingsPerMonth;

    @Min(value = 1, message = "Max storage (MB) must be at least 1")
    private Integer maxStorageMb;

    /** Revert every limit to the tenant's plan defaults and remove the override pin. */
    private boolean clearOverride;
}