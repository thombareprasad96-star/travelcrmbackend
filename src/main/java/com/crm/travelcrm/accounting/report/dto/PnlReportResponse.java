package com.crm.travelcrm.accounting.report.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Tax-aware P&L over a period (accrual by invoice/bill date). Revenue is the taxable value of issued
 * invoices — output GST and TCS are pass-through liabilities, NOT income. Cost is the vendor bills,
 * net of input GST when the tenant is ITC-eligible, PLUS the per-booking expense ledger.
 * Margin = revenue − net vendor cost − booking expenses.
 *
 * <p><b>Why booking expenses are a separate line rather than folded into {@code vendorCostNet}.</b>
 * They are a different book kept for a different reason: a {@code VendorBill} is a tax document with
 * GST and TDS attached, while a booking expense is the agency's own cash record against one booking
 * and carries neither. Merging them would put untaxed rows inside a figure whose whole purpose is to
 * net off input GST, and would quietly make {@code inputGstCredit} unreconcilable against
 * {@code vendorCostGross}. Kept apart, each line still ties back to the book it came from.
 *
 * <p><b>They can overlap, and the report does not pretend otherwise.</b> Nothing stops an agency from
 * recording the same spend as both a vendor bill and a booking expense — no link exists between the
 * two records to detect it. The lines are reported separately and labelled so the double entry is
 * VISIBLE in the breakdown instead of buried inside one merged total.
 */
@Getter
@Builder
public class PnlReportResponse {

    private final LocalDate from;
    private final LocalDate to;

    private final long invoiceCount;
    private final BigDecimal taxableRevenue;
    private final BigDecimal outputGst;
    private final BigDecimal tcsCollected;

    private final long purchaseCount;
    private final BigDecimal vendorCostGross;
    private final BigDecimal inputGstCredit;
    private final BigDecimal vendorCostNet;
    private final BigDecimal tdsDeducted;

    // ── Booking expense ledger (accrual by expenseDate) ──────────────────────
    // Supplier spend the agency itemised per booking, split from its own overhead so the margin can
    // be read either way. Both are subtracted from the margin; bookingExpenseTotal is their sum.
    private final long bookingExpenseCount;
    private final BigDecimal bookingExpenseVendor;
    private final BigDecimal bookingExpenseInternal;
    private final BigDecimal bookingExpenseTotal;

    private final BigDecimal grossMargin;
    private final BigDecimal grossMarginPct;
    private final BigDecimal netGstPayable;
    private final boolean inputTaxCreditEligible;

    private final List<MonthlyRow> monthly;

    @Getter
    @Builder
    public static class MonthlyRow {
        private final String month;           // e.g. "2026-04"
        private final BigDecimal taxableRevenue;
        private final BigDecimal outputGst;
        private final BigDecimal vendorCostNet;
        private final BigDecimal bookingExpenses;
        private final BigDecimal grossMargin;
    }
}