package com.crm.travelcrm.accounting.report.service;

import com.crm.travelcrm.accounting.invoice.entity.TaxInvoice;
import com.crm.travelcrm.accounting.invoice.entity.TaxInvoiceLine;
import com.crm.travelcrm.accounting.invoice.enums.InvoiceStatus;
import com.crm.travelcrm.accounting.invoice.enums.InvoiceType;
import com.crm.travelcrm.accounting.invoice.repository.TaxInvoiceLineRepository;
import com.crm.travelcrm.accounting.invoice.repository.TaxInvoiceRepository;
import com.crm.travelcrm.accounting.report.dto.GstSummaryResponse;
import com.crm.travelcrm.accounting.report.dto.PnlReportResponse;
import com.crm.travelcrm.accounting.settings.entity.AccountingSettings;
import com.crm.travelcrm.accounting.settings.repository.AccountingSettingsRepository;
import com.crm.travelcrm.accounting.tds.entity.VendorBill;
import com.crm.travelcrm.accounting.tds.enums.VendorBillStatus;
import com.crm.travelcrm.accounting.tds.repository.VendorBillRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Read-model over the accounting book: a tax-aware P&L and a GSTR-style GST summary. Accrual by
 * invoice/bill date; cancelled documents are excluded. Output GST and TCS are treated as pass-through
 * liabilities (not revenue), and vendor cost is netted of input GST when the tenant is ITC-eligible.
 */
@Service
@RequiredArgsConstructor
public class AccountingReportService {

    private final TaxInvoiceRepository invoiceRepository;
    private final TaxInvoiceLineRepository lineRepository;
    private final VendorBillRepository vendorBillRepository;
    private final AccountingSettingsRepository settingsRepository;

    // ── P&L ────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public PnlReportResponse pnl(Long tenantId, LocalDate from, LocalDate to) {
        LocalDate start = from != null ? from : LocalDate.now().withDayOfMonth(1);
        LocalDate end = to != null ? to : LocalDate.now();
        boolean itc = itcEligible(tenantId);

        List<TaxInvoice> invoices = invoiceRepository
                .findByTenantIdAndInvoiceDateBetweenAndDeletedAtIsNull(tenantId, start, end).stream()
                .filter(i -> i.getStatus() == InvoiceStatus.ISSUED).toList();
        List<VendorBill> bills = vendorBillRepository
                .findByTenantIdAndBillDateBetweenAndDeletedAtIsNull(tenantId, start, end).stream()
                .filter(b -> b.getStatus() != VendorBillStatus.CANCELLED).toList();

        BigDecimal taxableRevenue = BigDecimal.ZERO, outputGst = BigDecimal.ZERO, tcs = BigDecimal.ZERO;
        for (TaxInvoice i : invoices) {
            taxableRevenue = taxableRevenue.add(nz(i.getTaxableValue()));
            outputGst = outputGst.add(nz(i.getCgst())).add(nz(i.getSgst())).add(nz(i.getIgst())).add(nz(i.getCess()));
            tcs = tcs.add(nz(i.getTcs()));
        }

        BigDecimal vendorGross = BigDecimal.ZERO, inputGst = BigDecimal.ZERO, tds = BigDecimal.ZERO;
        for (VendorBill b : bills) {
            vendorGross = vendorGross.add(nz(b.getGrossAmount()));
            inputGst = inputGst.add(nz(b.getGstInput()));
            tds = tds.add(nz(b.getTdsAmount()));
        }
        BigDecimal inputCredit = itc ? inputGst : BigDecimal.ZERO;
        BigDecimal vendorNet = vendorGross.subtract(inputCredit);
        BigDecimal margin = taxableRevenue.subtract(vendorNet);
        BigDecimal marginPct = taxableRevenue.signum() > 0
                ? margin.multiply(BigDecimal.valueOf(100)).divide(taxableRevenue, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return PnlReportResponse.builder()
                .from(start).to(end)
                .invoiceCount(invoices.size())
                .taxableRevenue(scale(taxableRevenue)).outputGst(scale(outputGst)).tcsCollected(scale(tcs))
                .purchaseCount(bills.size())
                .vendorCostGross(scale(vendorGross)).inputGstCredit(scale(inputCredit))
                .vendorCostNet(scale(vendorNet)).tdsDeducted(scale(tds))
                .grossMargin(scale(margin)).grossMarginPct(marginPct)
                .netGstPayable(scale(outputGst.subtract(inputCredit)))
                .inputTaxCreditEligible(itc)
                .monthly(monthly(invoices, bills, itc))
                .build();
    }

    private List<PnlReportResponse.MonthlyRow> monthly(List<TaxInvoice> invoices, List<VendorBill> bills, boolean itc) {
        Map<YearMonth, BigDecimal[]> m = new TreeMap<>();  // [revenue, outputGst, vendorNet]
        for (TaxInvoice i : invoices) {
            BigDecimal[] a = m.computeIfAbsent(YearMonth.from(i.getInvoiceDate()), k -> zeros());
            a[0] = a[0].add(nz(i.getTaxableValue()));
            a[1] = a[1].add(nz(i.getCgst())).add(nz(i.getSgst())).add(nz(i.getIgst())).add(nz(i.getCess()));
        }
        for (VendorBill b : bills) {
            BigDecimal[] a = m.computeIfAbsent(YearMonth.from(b.getBillDate()), k -> zeros());
            BigDecimal net = nz(b.getGrossAmount()).subtract(itc ? nz(b.getGstInput()) : BigDecimal.ZERO);
            a[2] = a[2].add(net);
        }
        List<PnlReportResponse.MonthlyRow> rows = new ArrayList<>();
        for (Map.Entry<YearMonth, BigDecimal[]> e : m.entrySet()) {
            BigDecimal[] a = e.getValue();
            rows.add(PnlReportResponse.MonthlyRow.builder()
                    .month(e.getKey().toString())
                    .taxableRevenue(scale(a[0])).outputGst(scale(a[1]))
                    .vendorCostNet(scale(a[2])).grossMargin(scale(a[0].subtract(a[2])))
                    .build());
        }
        return rows;
    }

    // ── GST summary ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public GstSummaryResponse gstSummary(Long tenantId, LocalDate from, LocalDate to) {
        LocalDate start = from != null ? from : LocalDate.now().withDayOfMonth(1);
        LocalDate end = to != null ? to : LocalDate.now();
        boolean itc = itcEligible(tenantId);

        List<TaxInvoice> taxInvoices = invoiceRepository
                .findByTenantIdAndInvoiceDateBetweenAndDeletedAtIsNull(tenantId, start, end).stream()
                .filter(i -> i.getStatus() == InvoiceStatus.ISSUED)
                .filter(i -> i.getInvoiceType() == InvoiceType.TAX_INVOICE)
                .toList();

        Map<BigDecimal, BigDecimal[]> byRate = new LinkedHashMap<>();   // [taxable, cgst, sgst, igst, cess]
        long b2b = 0, b2c = 0;
        BigDecimal taxable = BigDecimal.ZERO, cgst = BigDecimal.ZERO, sgst = BigDecimal.ZERO,
                igst = BigDecimal.ZERO, cess = BigDecimal.ZERO, tcs = BigDecimal.ZERO;

        for (TaxInvoice i : taxInvoices) {
            if (StringUtils.hasText(i.getRecipientGstin())) b2b++; else b2c++;
            taxable = taxable.add(nz(i.getTaxableValue()));
            cgst = cgst.add(nz(i.getCgst()));
            sgst = sgst.add(nz(i.getSgst()));
            igst = igst.add(nz(i.getIgst()));
            cess = cess.add(nz(i.getCess()));
            tcs = tcs.add(nz(i.getTcs()));
            for (TaxInvoiceLine l : lineRepository.findByInvoiceIdAndTenantIdOrderByLineNoAsc(i.getId(), tenantId)) {
                BigDecimal[] a = byRate.computeIfAbsent(nz(l.getGstRatePct()), k -> zeros5());
                a[0] = a[0].add(nz(l.getTaxableValue()));
                a[1] = a[1].add(nz(l.getCgstAmt()));
                a[2] = a[2].add(nz(l.getSgstAmt()));
                a[3] = a[3].add(nz(l.getIgstAmt()));
                a[4] = a[4].add(nz(l.getCessAmt()));
            }
        }

        BigDecimal inputGst = BigDecimal.ZERO;
        for (VendorBill b : vendorBillRepository
                .findByTenantIdAndBillDateBetweenAndDeletedAtIsNull(tenantId, start, end)) {
            if (b.getStatus() != VendorBillStatus.CANCELLED) inputGst = inputGst.add(nz(b.getGstInput()));
        }
        BigDecimal outputGst = cgst.add(sgst).add(igst).add(cess);
        BigDecimal netPayable = outputGst.subtract(itc ? inputGst : BigDecimal.ZERO);

        List<GstSummaryResponse.RateRow> rateRows = new ArrayList<>();
        for (Map.Entry<BigDecimal, BigDecimal[]> e : byRate.entrySet()) {
            BigDecimal[] a = e.getValue();
            rateRows.add(GstSummaryResponse.RateRow.builder()
                    .gstRatePct(e.getKey())
                    .taxableValue(scale(a[0])).cgst(scale(a[1])).sgst(scale(a[2]))
                    .igst(scale(a[3])).cess(scale(a[4])).build());
        }

        return GstSummaryResponse.builder()
                .from(start).to(end)
                .outputByRate(rateRows)
                .b2bInvoiceCount(b2b).b2cInvoiceCount(b2c)
                .totalTaxable(scale(taxable)).totalCgst(scale(cgst)).totalSgst(scale(sgst))
                .totalIgst(scale(igst)).totalCess(scale(cess)).totalTcs(scale(tcs))
                .inputGst(scale(inputGst)).netGstPayable(scale(netPayable))
                .build();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private boolean itcEligible(Long tenantId) {
        return settingsRepository.findByTenantId(tenantId)
                .map(AccountingSettings::isInputTaxCreditEligible).orElse(false);
    }

    private static BigDecimal[] zeros() {
        return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
    }

    private static BigDecimal[] zeros5() {
        return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static BigDecimal scale(BigDecimal v) {
        return nz(v).setScale(2, RoundingMode.HALF_UP);
    }
}