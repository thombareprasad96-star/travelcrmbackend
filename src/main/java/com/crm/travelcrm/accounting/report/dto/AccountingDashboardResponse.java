package com.crm.travelcrm.accounting.report.dto;

import com.crm.travelcrm.accounting.invoice.dto.TaxInvoiceResponse;
import com.crm.travelcrm.accounting.tds.dto.VendorBillResponse;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The Accounts dashboard payload — KPI tiles, a revenue-vs-expenses trend, GST/TDS snapshot and the
 * recent-documents strips. Composed from the invoice, vendor-bill and P&L services over the period.
 */
@Getter
@Builder
public class AccountingDashboardResponse {

    private final LocalDate from;
    private final LocalDate to;

    // ── KPI tiles ─────────────────────────────────────────────────────────────
    private final BigDecimal totalRevenue;          // taxable turnover of issued invoices, in period
    private final BigDecimal outstandingReceivable; // current booking dues (totalPayable − paid)
    private final BigDecimal outstandingPayable;    // current vendor dues (netPayable − paid)
    private final BigDecimal grossMargin;
    private final BigDecimal grossMarginPct;

    private final long invoiceCount;
    private final long vendorBillCount;

    // ── GST / TDS snapshot ────────────────────────────────────────────────────
    private final BigDecimal outputGst;
    private final BigDecimal inputGstCredit;
    private final BigDecimal netGstPayable;
    private final BigDecimal tcsCollected;
    private final BigDecimal tdsDeducted;
    private final boolean inputTaxCreditEligible;

    // ── Trend + recent strips ─────────────────────────────────────────────────
    private final List<RevenueVsExpense> revenueVsExpenses;
    private final List<TaxInvoiceResponse> recentInvoices;
    private final List<VendorBillResponse> recentVendorBills;

    @Getter
    @Builder
    public static class RevenueVsExpense {
        private final String month;        // "2026-04"
        private final BigDecimal revenue;  // invoice taxable value
        private final BigDecimal expenses; // vendor cost (net of ITC where eligible)
    }
}
