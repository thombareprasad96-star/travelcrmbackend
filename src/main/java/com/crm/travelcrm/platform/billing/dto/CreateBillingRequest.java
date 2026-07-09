package com.crm.travelcrm.platform.billing.dto;

import com.crm.travelcrm.tenent.enums.TenantPlan;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Issue an invoice against a tenant. Everything is optional — the service fills sensible defaults
 * from the tenant's current plan (amount = plan price, currency = plan currency, plan = tenant plan)
 * and the current month (period = this month, due = issue + 15 days).
 */
@Data
public class CreateBillingRequest {

    @DecimalMin(value = "0.0", inclusive = true, message = "Amount cannot be negative")
    private BigDecimal amount;

    private String currency;

    private TenantPlan plan;

    private LocalDate periodStart;

    private LocalDate periodEnd;

    private LocalDate dueDate;

    @Size(max = 500, message = "Notes cannot exceed 500 characters")
    private String notes;
}