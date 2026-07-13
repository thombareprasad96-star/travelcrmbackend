package com.crm.travelcrm.platform.subscription.upgrade.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** SuperAdmin rejects a plan-upgrade request; the reason is surfaced to the tenant. */
@Data
public class RejectUpgradeRequestRequest {

    @NotBlank(message = "A rejection reason is required")
    @Size(max = 500, message = "Reason cannot exceed 500 characters")
    private String reason;
}