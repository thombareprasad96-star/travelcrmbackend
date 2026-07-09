package com.crm.travelcrm.tenent.dto;

import com.crm.travelcrm.tenent.enums.TenantPlan;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Assign / upgrade / downgrade a tenant's plan. */
@Data
public class ChangePlanRequest {

    @NotNull(message = "Plan is required")
    private TenantPlan plan;
}