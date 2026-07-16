package com.crm.travelcrm.accounting.invoice.enums;

/**
 * A logical invoice book, each with its own gap-free per-tenant, per-financial-year number series.
 * GST law requires a Bill of Supply and a Tax Invoice to run on <b>separate</b> consecutive series,
 * and credit/debit notes likewise — so every book gets its own {@code seriesCode}.
 *
 * <p>Codes are deliberately short: the printed number is {@code CODE/FYYY/NNNNN} (e.g.
 * {@code TI/2627/00001}) which must stay within the 16-character cap of Rule 46(b). The longest code
 * here ("BOS") yields {@code BOS/2627/00001} = 14 chars.
 */
public enum InvoiceSeries {

    /** Registered regular dealer, taxable supply — CGST/SGST or IGST shown. */
    TAX_INVOICE("TI"),
    /** Composition dealer / exempt or nil-rated supply — no tax charged. */
    BILL_OF_SUPPLY("BOS"),
    /** Non-GST / unregistered agency — a plain money invoice with no tax lines. */
    SIMPLE_INVOICE("INV"),
    /** GST credit note against an issued tax invoice (downward revision / return). */
    GST_CREDIT_NOTE("GCN"),
    /** GST debit note against an issued tax invoice (upward revision). */
    GST_DEBIT_NOTE("GDN");

    private final String seriesCode;

    InvoiceSeries(String seriesCode) {
        this.seriesCode = seriesCode;
    }

    /** Short prefix for the number series, e.g. "TI" → TI/2627/00001. */
    public String seriesCode() {
        return seriesCode;
    }
}