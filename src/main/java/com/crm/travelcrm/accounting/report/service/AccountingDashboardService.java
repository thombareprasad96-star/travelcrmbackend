package com.crm.travelcrm.accounting.report.service;

import com.crm.travelcrm.accounting.invoice.service.InvoiceService;
import com.crm.travelcrm.accounting.report.dto.AccountingDashboardResponse;
import com.crm.travelcrm.accounting.report.dto.PnlReportResponse;
import com.crm.travelcrm.accounting.tds.repository.VendorBillRepository;
import com.crm.travelcrm.accounting.tds.service.VendorPayableService;
import com.crm.travelcrm.booking.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Composes the Accounts dashboard from the P&L, invoice and vendor-bill services plus the current
 * receivable/payable snapshots. Read-only; the Hibernate tenant filter scopes the booking receivable
 * aggregate to the current tenant.
 */
@Service
@RequiredArgsConstructor
public class AccountingDashboardService {

    private static final int RECENT = 5;

    private final AccountingReportService reportService;
    private final InvoiceService invoiceService;
    private final VendorPayableService vendorPayableService;
    private final VendorBillRepository vendorBillRepository;
    private final BookingRepository bookingRepository;

    @Transactional(readOnly = true)
    public AccountingDashboardResponse dashboard(Long tenantId, LocalDate from, LocalDate to) {
        PnlReportResponse pnl = reportService.pnl(tenantId, from, to);

        BigDecimal receivable = nz(bookingRepository.sumTotalPending());
        BigDecimal payable = nz(vendorBillRepository.sumOutstandingPayable(tenantId));

        List<AccountingDashboardResponse.RevenueVsExpense> trend = pnl.getMonthly().stream()
                .map(m -> AccountingDashboardResponse.RevenueVsExpense.builder()
                        .month(m.getMonth())
                        .revenue(m.getTaxableRevenue())
                        .expenses(m.getVendorCostNet())
                        .build())
                .toList();

        return AccountingDashboardResponse.builder()
                .from(pnl.getFrom()).to(pnl.getTo())
                .totalRevenue(pnl.getTaxableRevenue())
                .outstandingReceivable(receivable)
                .outstandingPayable(payable)
                .grossMargin(pnl.getGrossMargin())
                .grossMarginPct(pnl.getGrossMarginPct())
                .invoiceCount(pnl.getInvoiceCount())
                .vendorBillCount(pnl.getPurchaseCount())
                .outputGst(pnl.getOutputGst())
                .inputGstCredit(pnl.getInputGstCredit())
                .netGstPayable(pnl.getNetGstPayable())
                .tcsCollected(pnl.getTcsCollected())
                .tdsDeducted(pnl.getTdsDeducted())
                .inputTaxCreditEligible(pnl.isInputTaxCreditEligible())
                .revenueVsExpenses(trend)
                .recentInvoices(invoiceService.list(tenantId, PageRequest.of(0, RECENT)).getContent())
                .recentVendorBills(vendorPayableService.list(tenantId, PageRequest.of(0, RECENT)).getContent())
                .build();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}