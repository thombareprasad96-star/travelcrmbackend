package com.crm.travelcrm.platform.subscription.dto;

import com.crm.travelcrm.tenent.enums.TenantPlan;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

/** A plan and its entitlements. Externalises {@code publicId} only. */
@Data
@Builder
public class PlanResponse {
    private UUID publicId;
    private TenantPlan code;
    private String displayName;
    private BigDecimal monthlyPrice;
    private String currency;
    private Integer maxUsers;             // null = unlimited
    private Integer maxLeads;             // null = unlimited
    private Integer maxBookingsPerMonth;  // null = unlimited
    private Integer maxStorageMb;         // null = unlimited
    private Set<String> modules;
    private boolean active;
}