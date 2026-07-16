package com.crm.travelcrm.accounting.report.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Tax-aware P&L over a period (accrual by invoice/bill date). Revenue is the taxable value of issued
 * invoices — output GST and TCS are pass-through liabilities, NOT income. Cost is the vendor bills,
 * net of input GST when the tenant is ITC-eligible. Margin = revenue − net cost.
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
        private final BigDecimal grossMargin;
    }
}