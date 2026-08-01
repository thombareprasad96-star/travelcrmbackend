package com.crm.travelcrm.accounting.settings.entity;

import com.crm.travelcrm.accounting.settings.enums.GstScheme;
import com.crm.travelcrm.accounting.settings.enums.TcsApplicability;
import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;

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

    // ── Booking-level tax (the figures on the booking itself) ────────────────────
    //
    // These drive Booking.gst / Booking.tcs / Booking.totalPayable — the amount the customer is
    // actually asked to pay — and they are per-TENANT because the right answer differs by tenant.
    // They were previously platform-wide properties (app.booking.gst-rate / app.booking.tcs-rate),
    // a flat 5% + 5% applied to every booking of every tenant including domestic ones, which the
    // code itself flagged as wrong for Indian tax.
    //
    // Distinct from the fields above, which govern the STATUTORY GST INVOICE issued out of the
    // accounting module (per-line HSN rates, place-of-supply split, slabbed TCS). Those two
    // surfaces are not reconciled today; see docs/booking-financial-engine-plan.md §6.4.

    /**
     * Whether GST is added on top of {@code customerAmount} when a booking's totals are computed.
     * A tenant that quotes GST-inclusive prices, or that is not GST-registered, turns this off.
     */
    @Builder.Default
    @Column(name = "apply_gst_on_bookings", nullable = false)
    private boolean applyGstOnBookings = true;

    /**
     * Booking GST rate as a PERCENT (5.00 = 5%), not a fraction — it is shown to and edited by the
     * tenant, and a percent is what they think in. Ignored when {@link #applyGstOnBookings} is off.
     */
    @Builder.Default
    @Column(name = "booking_gst_rate_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal bookingGstRatePct = new BigDecimal("5.00");

    /**
     * When this tenant collects TCS. Defaults to {@link TcsApplicability#ALWAYS} ONLY to preserve
     * the pre-existing platform behaviour on upgrade — see the enum's javadoc. A domestic operator
     * should change it.
     */
    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "tcs_applicability", nullable = false, length = 20)
    private TcsApplicability tcsApplicability = TcsApplicability.ALWAYS;

    /** Booking TCS rate as a PERCENT (5.00 = 5%). Ignored when TCS does not apply. */
    @Builder.Default
    @Column(name = "booking_tcs_rate_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal bookingTcsRatePct = new BigDecimal("5.00");

    // ── Statutory TCS slab (the GST tax invoice path) ────────────────────────────
    //
    // Used by TcsCalculator when an overseas tour package is INVOICED. Separate from the booking
    // rate above: that one is the simple figure shown on the booking, this is the slabbed statutory
    // computation against the buyer's annual threshold.
    //
    // These are per-tenant AND overridable because the statute keeps moving and tenants adopt
    // changes on different dates with their CA's sign-off. Defaults are the historical
    // 5% / 20% either side of ₹7,00,000. NOTE: as of 1 Apr 2026 the law is a FLAT 2% with NO
    // threshold (s.394(1), Income-tax Act 2025) — the defaults are deliberately left at the old
    // values so upgrading changes nobody's tax silently; a tenant moves to the new regime by
    // setting the rates themselves, on their CA's advice.

    /** Annual per-buyer threshold in rupees, above which the higher slab applies. */
    @Builder.Default
    @Column(name = "tcs_threshold", nullable = false, precision = 14, scale = 2)
    private BigDecimal tcsThreshold = new BigDecimal("700000.00");

    /** PERCENT applied up to {@link #tcsThreshold}. Set equal to the above-rate for a flat regime. */
    @Builder.Default
    @Column(name = "tcs_rate_below_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal tcsRateBelowPct = new BigDecimal("5.00");

    /** PERCENT applied beyond {@link #tcsThreshold}. */
    @Builder.Default
    @Column(name = "tcs_rate_above_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal tcsRateAbovePct = new BigDecimal("20.00");

    // ── TDS withheld when paying a vendor ────────────────────────────────────────
    // Section rates change by Finance Act, and which section a tenant uses depends on their vendor
    // mix. Defaults are the standard rates; 206AA (no PAN ⇒ the higher of the section rate and the
    // no-PAN rate) is applied on top by TdsCalculator and is not itself configurable — it is a
    // statutory rule, not a rate.

    /** PERCENT — s.194C, contractors (transport, DMC handling). */
    @Builder.Default
    @Column(name = "tds_194c_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal tds194cPct = new BigDecimal("2.00");

    /** PERCENT — s.194H, commission and brokerage. */
    @Builder.Default
    @Column(name = "tds_194h_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal tds194hPct = new BigDecimal("5.00");

    /** PERCENT — s.194J, professional and technical services. */
    @Builder.Default
    @Column(name = "tds_194j_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal tds194jPct = new BigDecimal("10.00");

    /** PERCENT — s.206AA floor when the vendor has furnished no PAN. */
    @Builder.Default
    @Column(name = "tds_no_pan_pct", nullable = false, precision = 5, scale = 2)
    private BigDecimal tdsNoPanPct = new BigDecimal("20.00");
}