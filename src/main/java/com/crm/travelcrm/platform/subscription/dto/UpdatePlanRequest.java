package com.crm.travelcrm.platform.subscription.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Edit a plan's entitlements/pricing. The plan {@code code} is immutable. {@code maxUsers} /
 * {@code maxLeads} left null = unlimited.
 */
@Data
public class UpdatePlanRequest {

    @NotBlank(message = "Display name is required")
    private String displayName;

    @NotNull(message = "Monthly price is required")
    @DecimalMin(value = "0.0", message = "Price cannot be negative")
    private BigDecimal monthlyPrice;

    @NotBlank(message = "Currency is required")
    private String currency;

    @Min(value = 1, message = "Max users must be at least 1")
    private Integer maxUsers;

    @Min(value = 1, message = "Max leads must be at least 1")
    private Integer maxLeads;

    @Min(value = 1, message = "Max bookings per month must be at least 1")
    private Integer maxBookingsPerMonth;

    @Min(value = 1, message = "Max storage (MB) must be at least 1")
    private Integer maxStorageMb;

    /** Sub-agents this plan grants (gated capability): null/0 = none. */
    @Min(value = 0, message = "Max sub-agents cannot be negative")
    private Integer maxSubAgents;

    private Set<String> modules;

    private boolean active;
}