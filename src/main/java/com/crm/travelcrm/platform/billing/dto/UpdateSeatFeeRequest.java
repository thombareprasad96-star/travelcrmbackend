package com.crm.travelcrm.platform.billing.dto;

import jakarta.validation.constraints.DecimalMin;
import lombok.Data;

import java.math.BigDecimal;

/**
 * SuperAdmin sets a tenant's per-tenant sub-agent seat fee. {@code monthlySeatFee = null} clears the
 * override so the tenant reverts to the platform-flat default
 * ({@code PlatformConfig billing.subagent.seat-fee}).
 */
@Data
public class UpdateSeatFeeRequest {

    @DecimalMin(value = "0.0", message = "Seat fee cannot be negative")
    private BigDecimal monthlySeatFee;
}
