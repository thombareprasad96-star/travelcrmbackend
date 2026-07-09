package com.crm.travelcrm.tenent.dto;

import com.crm.travelcrm.tenent.enums.TenantPlan;
import com.crm.travelcrm.tenent.enums.TenantStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Lean tenant row for the console list (search/filter/paginated). Externalises {@code publicId}
 * only. {@code userCount} is the tenant's active (non-deleted) user count.
 */
@Data
@Builder
public class TenantSummaryResponse {
    private UUID publicId;
    private String organizationName;
    private String organizationCode;
    private String email;
    private String phone;
    private TenantPlan plan;
    private TenantStatus status;
    private LocalDate subscriptionEndDate;
    private Integer maxUsers;
    private long userCount;
    private long unpaidCount;   // number of UNPAID invoices — drives the list "unpaid" badge
    private LocalDateTime createdAt;
    private boolean deleted;
}