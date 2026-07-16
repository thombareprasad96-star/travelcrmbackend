package com.crm.travelcrm.accounting.settings.entity;

import com.crm.travelcrm.accounting.settings.enums.GstScheme;
import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * Per-tenant accounting/GST configuration — exactly one row per tenant (lazily created on first
 * access, like {@code Company}). Holds the tenant-wide switches that shape invoicing: the GST
 * registration scheme (which decides whether a Tax Invoice can be raised at all), whether TCS is
 * auto-applied to overseas tour packages, and whether invoice totals are rounded to the rupee.
 */
@Entity
@Table(
        name = "accounting_settings",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_accounting_settings_tenant", columnNames = "tenant_id")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class AccountingSettings extends BaseTenantEntity {

    /** Registration scheme — the master switch for "can this tenant issue a GST invoice?". */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "gst_scheme", nullable = false, length = 20)
    private GstScheme gstScheme = GstScheme.REGULAR;

    /**
     * Auto-apply TCS under Section 206C(1G) when a booking flagged as an overseas tour package is
     * invoiced. When false, the accountant applies TCS manually. Domestic packages never attract it.
     */
    @Builder.Default
    @Column(name = "auto_tcs_on_overseas", nullable = false)
    private boolean autoTcsOnOverseas = true;

    /** Round the invoice grand total to the nearest rupee and show the round-off line. */
    @Builder.Default
    @Column(name = "round_invoice_total", nullable = false)
    private boolean roundInvoiceTotal = true;

    /**
     * Whether the tenant qualifies for input tax credit. Tour operators on the 5%-no-ITC scheme set
     * this false so the P&L treats input GST as a cost, not a recoverable credit.
     */
    @Builder.Default
    @Column(name = "input_tax_credit_eligible", nullable = false)
    private boolean inputTaxCreditEligible = false;
}