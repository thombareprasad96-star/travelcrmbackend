package com.crm.travelcrm.accounting.settings.dto;

import com.crm.travelcrm.accounting.settings.enums.GstScheme;
import com.crm.travelcrm.accounting.settings.enums.TcsApplicability;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/** Read view of the tenant's accounting/GST settings. */
@Getter
@Builder
public class AccountingSettingsDto {

    private final GstScheme gstScheme;
    /** Derived: true only when the scheme can raise a GST Tax Invoice (REGULAR). */
    private final boolean canIssueTaxInvoice;
    private final boolean autoTcsOnOverseas;
    private final boolean roundInvoiceTotal;
    private final boolean inputTaxCreditEligible;
    /** The tenant's own GSTIN from the Company profile, echoed for convenience (may be null). */
    private final String supplierGstin;
    private final String supplierStateCode;

    // ── Booking-level tax — what the customer is actually charged ────────────────
    private final boolean applyGstOnBookings;
    /** PERCENT, e.g. 5.00 = 5%. */
    private final BigDecimal bookingGstRatePct;
    private final TcsApplicability tcsApplicability;
    /** PERCENT, e.g. 5.00 = 5%. */
    private final BigDecimal bookingTcsRatePct;

    // ── Statutory TCS slab (GST invoice path) ───────────────────────────────────
    private final BigDecimal tcsThreshold;
    private final BigDecimal tcsRateBelowPct;
    private final BigDecimal tcsRateAbovePct;

    // ── TDS on vendor bills ─────────────────────────────────────────────────────
    private final BigDecimal tds194cPct;
    private final BigDecimal tds194hPct;
    private final BigDecimal tds194jPct;
    private final BigDecimal tdsNoPanPct;
}