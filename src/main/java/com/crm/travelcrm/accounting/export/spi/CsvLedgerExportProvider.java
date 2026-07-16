package com.crm.travelcrm.accounting.export.spi;

import com.crm.travelcrm.accounting.invoice.entity.TaxInvoice;
import com.crm.travelcrm.accounting.invoice.repository.TaxInvoiceRepository;
import com.crm.travelcrm.accounting.tds.entity.VendorBill;
import com.crm.travelcrm.accounting.tds.repository.VendorBillRepository;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * Default ledger export — emits a single generic CSV of sales invoices and vendor bills over the
 * period, ready to map into Tally/Zoho. A real Tally-XML or Zoho-API provider supersedes this bean.
 * Only the {@code CSV} format is supported here; other formats return {@link LedgerExport#unsupported}.
 */
public class CsvLedgerExportProvider implements LedgerExportProvider {

    private final TaxInvoiceRepository invoiceRepository;
    private final VendorBillRepository vendorBillRepository;

    public CsvLedgerExportProvider(TaxInvoiceRepository invoiceRepository,
                                   VendorBillRepository vendorBillRepository) {
        this.invoiceRepository = invoiceRepository;
        this.vendorBillRepository = vendorBillRepository;
    }

    @Override
    public LedgerExport export(Long tenantId, LocalDate from, LocalDate to, String format) {
        String fmt = format == null ? "CSV" : format.trim().toUpperCase();
        if (!"CSV".equals(fmt)) {
            return LedgerExport.unsupported(
                    "Format '" + fmt + "' needs a dedicated export provider (not configured). CSV is available.");
        }
        LocalDate start = from != null ? from : LocalDate.now().withDayOfMonth(1);
        LocalDate end = to != null ? to : LocalDate.now();

        StringBuilder sb = new StringBuilder();
        sb.append("record_type,number,date,party,gstin,taxable,cgst,sgst,igst,cess,tcs,tds,total,status\n");

        for (TaxInvoice inv : invoiceRepository
                .findByTenantIdAndInvoiceDateBetweenAndDeletedAtIsNull(tenantId, start, end)) {
            row(sb, "SALES", inv.getInvoiceNumber(), inv.getInvoiceDate(), inv.getRecipientName(),
                    inv.getRecipientGstin(), inv.getTaxableValue(), inv.getCgst(), inv.getSgst(),
                    inv.getIgst(), inv.getCess(), inv.getTcs(), BigDecimal.ZERO, inv.getInvoiceTotal(),
                    inv.getStatus().name());
        }
        for (VendorBill bill : vendorBillRepository
                .findByTenantIdAndBillDateBetweenAndDeletedAtIsNull(tenantId, start, end)) {
            row(sb, "PURCHASE",
                    bill.getBillNumber() != null ? bill.getBillNumber() : bill.getPublicId().toString(),
                    bill.getBillDate(), bill.getVendorNameSnapshot(), bill.getGstinSnapshot(),
                    bill.getTdsBase(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, bill.getTdsAmount(), bill.getGrossAmount(), bill.getStatus().name());
        }

        String filename = "ledger-" + start + "-to-" + end + ".csv";
        return LedgerExport.of(filename, "text/csv", sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void row(StringBuilder sb, String type, String number, LocalDate date, String party,
                            String gstin, BigDecimal taxable, BigDecimal cgst, BigDecimal sgst,
                            BigDecimal igst, BigDecimal cess, BigDecimal tcs, BigDecimal tds,
                            BigDecimal total, String status) {
        sb.append(type).append(',')
          .append(csv(number)).append(',')
          .append(date != null ? date.toString() : "").append(',')
          .append(csv(party)).append(',')
          .append(csv(gstin)).append(',')
          .append(num(taxable)).append(',')
          .append(num(cgst)).append(',')
          .append(num(sgst)).append(',')
          .append(num(igst)).append(',')
          .append(num(cess)).append(',')
          .append(num(tcs)).append(',')
          .append(num(tds)).append(',')
          .append(num(total)).append(',')
          .append(csv(status)).append('\n');
    }

    private static String num(BigDecimal v) {
        return (v != null ? v : BigDecimal.ZERO).toPlainString();
    }

    /** RFC-4180 field escaping. */
    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}