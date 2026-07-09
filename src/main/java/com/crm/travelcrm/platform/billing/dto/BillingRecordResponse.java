package com.crm.travelcrm.platform.billing.dto;

import com.crm.travelcrm.platform.billing.enums.BillingStatus;
import com.crm.travelcrm.tenent.enums.TenantPlan;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** A platform invoice. Externalises {@code publicId} only; {@code overdue} is derived. */
@Data
@Builder
public class BillingRecordResponse {
    private UUID publicId;
    private String invoiceNumber;
    private String tenantCode;
    private String tenantName;
    private TenantPlan plan;
    private BigDecimal amount;
    private String currency;
    private LocalDate periodStart;
    private LocalDate periodEnd;
    private LocalDate issueDate;
    private LocalDate dueDate;
    private LocalDate paidDate;
    private BillingStatus status;
    private boolean overdue;
    private String notes;
    private LocalDateTime createdAt;
}