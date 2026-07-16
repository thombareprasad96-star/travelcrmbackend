package com.crm.travelcrm.platform.billing.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * SuperAdmin sets the platform-wide sub-agent (Travel Partner) seat pricing. Applies to ALL tenants.
 * A null {@code oneTimeLicenseFee} clears the explicit one-time fee so it falls back to the recurring
 * rate (never left at 0 by accident).
 */
@Data
public class UpdateSubAgentPricingRequest {

    @NotNull(message = "recurringSeatFee is required")
    @DecimalMin(value = "0.0", message = "Recurring seat fee must be zero or positive")
    private BigDecimal recurringSeatFee;

    /** Optional. null = clear (fall back to the recurring rate). */
    @DecimalMin(value = "0.0", message = "One-time license fee must be zero or positive")
    private BigDecimal oneTimeLicenseFee;
}
