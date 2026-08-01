package com.crm.travelcrm.accounting.settings.dto;

import com.crm.travelcrm.accounting.settings.enums.GstScheme;
import com.crm.travelcrm.accounting.settings.enums.TcsApplicability;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** Partial update of the tenant's accounting/GST settings — null fields are left unchanged. */
@Getter
@Setter
public class UpdateAccountingSettingsRequest {

    private GstScheme gstScheme;
    private Boolean autoTcsOnOverseas;
    private Boolean roundInvoiceTotal;
    private Boolean inputTaxCreditEligible;

    // ── Booking-level tax — what the customer is actually charged ────────────────

    /** Add GST on top of customerAmount when computing a booking's totals. */
    private Boolean applyGstOnBookings;

    /** Booking GST rate as a PERCENT (5.00 = 5%). */
    @DecimalMin(value = "0.0", message = "GST rate cannot be negative")
    @DecimalMax(value = "100.0", message = "GST rate cannot exceed 100%")
    @Digits(integer = 3, fraction = 2, message = "Invalid GST rate")
    private BigDecimal bookingGstRatePct;

    /** NEVER (domestic-only operator) / OVERSEAS_ONLY (matches the statute) / ALWAYS. */
    private TcsApplicability tcsApplicability;

    /** Booking TCS rate as a PERCENT (5.00 = 5%). */
    @DecimalMin(value = "0.0", message = "TCS rate cannot be negative")
    @DecimalMax(value = "100.0", message = "TCS rate cannot exceed 100%")
    @Digits(integer = 3, fraction = 2, message = "Invalid TCS rate")
    private BigDecimal bookingTcsRatePct;

    // ── Statutory TCS slab (GST invoice path) ───────────────────────────────────

    /** Annual per-buyer threshold in rupees. Set both rates equal for a flat, thresholdless regime. */
    @DecimalMin(value = "0.0", message = "TCS threshold cannot be negative")
    @Digits(integer = 12, fraction = 2, message = "Invalid TCS threshold")
    private BigDecimal tcsThreshold;

    @DecimalMin(value = "0.0", message = "TCS rate cannot be negative")
    @DecimalMax(value = "100.0", message = "TCS rate cannot exceed 100%")
    @Digits(integer = 3, fraction = 2, message = "Invalid TCS rate")
    private BigDecimal tcsRateBelowPct;

    @DecimalMin(value = "0.0", message = "TCS rate cannot be negative")
    @DecimalMax(value = "100.0", message = "TCS rate cannot exceed 100%")
    @Digits(integer = 3, fraction = 2, message = "Invalid TCS rate")
    private BigDecimal tcsRateAbovePct;

    // ── TDS on vendor bills ─────────────────────────────────────────────────────

    @DecimalMin(value = "0.0", message = "TDS rate cannot be negative")
    @DecimalMax(value = "100.0", message = "TDS rate cannot exceed 100%")
    @Digits(integer = 3, fraction = 2, message = "Invalid TDS rate")
    private BigDecimal tds194cPct;

    @DecimalMin(value = "0.0", message = "TDS rate cannot be negative")
    @DecimalMax(value = "100.0", message = "TDS rate cannot exceed 100%")
    @Digits(integer = 3, fraction = 2, message = "Invalid TDS rate")
    private BigDecimal tds194hPct;

    @DecimalMin(value = "0.0", message = "TDS rate cannot be negative")
    @DecimalMax(value = "100.0", message = "TDS rate cannot exceed 100%")
    @Digits(integer = 3, fraction = 2, message = "Invalid TDS rate")
    private BigDecimal tds194jPct;

    /** s.206AA floor when the vendor has no PAN. The "higher of" rule itself is not configurable. */
    @DecimalMin(value = "0.0", message = "TDS rate cannot be negative")
    @DecimalMax(value = "100.0", message = "TDS rate cannot exceed 100%")
    @Digits(integer = 3, fraction = 2, message = "Invalid TDS rate")
    private BigDecimal tdsNoPanPct;
}