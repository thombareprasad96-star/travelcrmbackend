package com.crm.travelcrm.accounting.invoice.dto;

import com.crm.travelcrm.accounting.invoice.enums.InvoiceType;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Request to issue an invoice for a booking. {@code invoiceType} is the "GST or not?" choice — if
 * omitted it defaults to what the tenant's GST scheme allows. Lines are auto-assembled from the
 * booking's service items when {@code lines} is null; a caller may override them for a bespoke invoice.
 */
@Getter
@Setter
public class IssueInvoiceRequest {

    @NotNull
    private UUID bookingPublicId;

    /** Tax Invoice / Bill of Supply / Simple Invoice. Null → the tenant scheme's default. */
    private InvoiceType invoiceType;

    /** Invoice date (drives the financial year and number series). Null → today. */
    private LocalDate invoiceDate;

    /** Overseas tour package → 206C(1G) TCS is applied (subject to the tenant's auto-TCS setting). */
    private Boolean overseasTourPackage;

    /** Force TCS on/off regardless of the tenant default (null → use the tenant default). */
    private Boolean applyTcs;

    /** The buyer's prior overseas package spend this FY, for the ₹7L TCS slab (null → 0). */
    private BigDecimal priorOverseasSpendThisFy;

    private Boolean reverseCharge;

    /** Override the recipient GSTIN (B2B). Null → taken from the customer, else B2C. */
    private String recipientGstin;

    /** Override the place-of-supply state (2-digit code or name). Null → derived from the customer. */
    private String placeOfSupplyState;

    /** Optional explicit lines; when null the invoice is built from the booking's service items. */
    private List<IssueInvoiceLineRequest> lines;
}