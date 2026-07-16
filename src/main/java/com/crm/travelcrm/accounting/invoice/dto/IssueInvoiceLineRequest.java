package com.crm.travelcrm.accounting.invoice.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One explicit invoice line. {@code hsnSac} resolves the GST rate from the tenant's rate master unless
 * {@code gstRatePct} is given directly.
 */
@Getter
@Setter
public class IssueInvoiceLineRequest {

    @NotNull
    private String description;

    private String hsnSac;

    @NotNull
    @Positive
    private BigDecimal taxableValue;

    /** Explicit GST rate; when null it is resolved from {@code hsnSac} (else the tenant default). */
    private BigDecimal gstRatePct;

    private BigDecimal cessPct;
}