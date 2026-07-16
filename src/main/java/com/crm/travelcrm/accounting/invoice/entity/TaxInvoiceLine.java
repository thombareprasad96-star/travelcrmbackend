package com.crm.travelcrm.accounting.invoice.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

/**
 * One persisted line of an issued {@link TaxInvoice} — the per-line taxable value + HSN/SAC + tax
 * breakup that GSTR filings and the P&L rollup query over. Kept alongside the frozen model snapshot so
 * the figures are queryable without deserialising JSON. Logical FK to {@code tax_invoices.id}.
 */
@Entity
@Table(
        name = "tax_invoice_lines",
        indexes = {
                @Index(name = "idx_taxinvline_tenant",  columnList = "tenant_id"),
                @Index(name = "idx_taxinvline_invoice", columnList = "tenant_id,invoice_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class TaxInvoiceLine extends BaseTenantEntity {

    @Column(name = "invoice_id", nullable = false)
    private Long invoiceId;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "description", length = 300)
    private String description;

    @Column(name = "hsn_sac", length = 12)
    private String hsnSac;

    @Builder.Default
    @Column(name = "taxable_value", nullable = false, precision = 14, scale = 2)
    private BigDecimal taxableValue = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "gst_rate_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal gstRatePct = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "cgst_amt", nullable = false, precision = 14, scale = 2)
    private BigDecimal cgstAmt = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "sgst_amt", nullable = false, precision = 14, scale = 2)
    private BigDecimal sgstAmt = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "igst_amt", nullable = false, precision = 14, scale = 2)
    private BigDecimal igstAmt = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "cess_amt", nullable = false, precision = 14, scale = 2)
    private BigDecimal cessAmt = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "line_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal lineTotal = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "itc_eligible", nullable = false)
    private boolean itcEligible = false;

    /** The booking service line this invoice line came from, if any. */
    @Column(name = "service_item_id")
    private Long serviceItemId;
}