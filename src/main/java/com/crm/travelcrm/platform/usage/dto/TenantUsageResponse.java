package com.crm.travelcrm.platform.usage.dto;

import com.crm.travelcrm.tenent.enums.TenantPlan;
import com.crm.travelcrm.tenent.enums.TenantStatus;
import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/** One tenant's live usage vs its plan/override quota. Externalises {@code publicId} only. */
@Data
@Builder
public class TenantUsageResponse {

    private UUID tenantPublicId;
    private String organizationName;
    private String organizationCode;
    private TenantPlan plan;
    private TenantStatus status;
    /** True when a SuperAdmin has pinned this tenant's limits (plan-change won't overwrite them). */
    private boolean quotaOverride;

    // ── Users (active, non-deleted) vs seat cap ──
    private long activeUsers;
    private Integer maxUsers;              // null = unlimited
    private Double usersPercent;           // null = unlimited
    private boolean usersOverLimit;
    private boolean usersNearLimit;

    // ── New bookings in the current calendar month vs monthly cap ──
    private long bookingsThisMonth;
    private Integer maxBookingsPerMonth;   // null = unlimited
    private Double bookingsPercent;
    private boolean bookingsOverLimit;
    private boolean bookingsNearLimit;

    // ── Storage (Cloudinary assets + traveler documents) vs cap ──
    private long storageUsedBytes;
    private Integer maxStorageMb;          // null = unlimited
    private Double storagePercent;
    private boolean storageOverLimit;
    private boolean storageNearLimit;

    // ── B2B sub-agents provisioned vs gated cap (0 = feature not granted) ──
    private long subAgents;
    private Integer maxSubAgents;          // 0/null-normalised-to-0 = none allowed (gated capability)
    private Double subAgentsPercent;
    private boolean subAgentsOverLimit;
    private boolean subAgentsNearLimit;
}