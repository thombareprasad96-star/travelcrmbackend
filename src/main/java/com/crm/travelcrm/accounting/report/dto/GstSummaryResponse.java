package com.crm.travelcrm.accounting.report.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A GSTR-style output-tax summary over a period: taxable value and CGST/SGST/IGST/cess grouped by rate
 * (from issued tax invoices), the B2B/B2C split, TCS collected, and the input GST available as ITC
 * from vendor bills.
 */
@Getter
@Builder
public class GstSummaryResponse {

    private final LocalDate from;
    private final LocalDate to;

    private final List<RateRow> outputByRate;

    private final long b2bInvoiceCount;
    private final long b2cInvoiceCount;

    private final BigDecimal totalTaxable;
    private final BigDecimal totalCgst;
    private final BigDecimal totalSgst;
    private final BigDecimal totalIgst;
    private final BigDecimal totalCess;
    private final BigDecimal totalTcs;

    /** Input GST from vendor bills (claimable as ITC when the tenant is eligible). */
    private final BigDecimal inputGst;
    private final BigDecimal netGstPayable;

    @Getter
    @Builder
    public static class RateRow {
        private final BigDecimal gstRatePct;
        private final BigDecimal taxableValue;
        private final BigDecimal cgst;
        private final BigDecimal sgst;
        private final BigDecimal igst;
        private final BigDecimal cess;
    }
}