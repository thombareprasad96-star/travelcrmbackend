package com.crm.travelcrm.booking.cancellation.dto;

import com.crm.travelcrm.booking.cancellation.enums.DeductionType;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The fully itemised result of computing a cancellation — every rupee shown, nothing implicit. This
 * is the backend's authoritative figure set; the frontend only displays it and never recomputes.
 *
 * <p>Sign convention: {@link #refundDue} is signed — positive means the agency refunds the customer,
 * negative means the customer still owes (see {@link #customerBalanceOwed}). It is never clamped.
 *
 * <p>The three vendor/profit fields ({@link #sunkVendorCost}, {@link #vendorRecoverable},
 * {@link #revisedNetProfit}) are agency-internal. They are safe on this quote because previewing /
 * performing a cancellation requires {@code BOOKING_CANCEL}, which base agents do not hold — but they
 * must NEVER be copied onto a customer-facing document model.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CancellationQuote {

    private UUID bookingPublicId;

    // ── Timing / band ─────────────────────────────────────────────────────────
    private LocalDate travelDate;
    private LocalDate cancelDate;
    /** Raw days from cancel date to travel date; negative if travel has already passed (no-show). */
    private int daysBeforeDeparture;
    /** {@code max(0, daysBeforeDeparture)} — the value the band is selected against. */
    private int effectiveDays;

    private Integer appliedBandMinDays;
    private DeductionType appliedDeductionType;
    private BigDecimal appliedDeductionValue;

    /** True when no policy could be resolved — charge is 0 and the cancel requires an elevated override. */
    private boolean noPolicy;

    // ── Retained charge (what the agency keeps) ───────────────────────────────
    /** The pre-tax base the charge is computed on (the booking's customerAmount). */
    private BigDecimal baseConsidered;
    /** Charge the policy computed, before any override (clamped to baseConsidered). */
    private BigDecimal systemComputedChargeBase;
    /** Final retained base after an optional override/waiver (equals systemComputedChargeBase if none). */
    private BigDecimal chargeBase;
    private boolean overrideApplied;

    private boolean gstOnChargeApplicable;
    private boolean tcsRefundable;
    /** GST retained on the charge, derived proportionally from the booking's STORED gst (rate-immune). */
    private BigDecimal gstOnCharge;
    /** TCS retained (zero when the policy treats TCS as refundable). */
    private BigDecimal tcsRetained;
    /** chargeBase + gstOnCharge + tcsRetained — the total amount kept. */
    private BigDecimal totalRetained;

    // ── Settlement (customer side) ────────────────────────────────────────────
    private BigDecimal paidAmount;
    /** paidAmount − totalRetained. Signed: negative ⇒ customer owes. */
    private BigDecimal refundDue;
    /** {@code max(0, refundDue)} — what actually gets refunded. */
    private BigDecimal refundToCustomer;
    /** {@code max(0, −refundDue)} — what the customer still owes after retention. */
    private BigDecimal customerBalanceOwed;
    /** True when refundDue < 0 (issue a debit note, not a refund voucher). */
    private boolean customerOwes;

    // ── Agency P&L (internal — never on a customer document) ──────────────────
    private BigDecimal sunkVendorCost;
    private BigDecimal vendorRecoverable;
    private BigDecimal revisedNetProfit;
}
