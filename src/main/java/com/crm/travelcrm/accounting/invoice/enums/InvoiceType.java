package com.crm.travelcrm.accounting.invoice.enums;

/**
 * The kind of invoice the accountant chooses at issue time — this is the concrete "GST invoice or
 * not?" selection. Each maps to its own {@link InvoiceSeries} (a separate gap-free book) and declares
 * whether GST is charged on it.
 */
public enum InvoiceType {

    /** Registered regular dealer — GST charged (CGST/SGST or IGST). Only allowed for the REGULAR scheme. */
    TAX_INVOICE(InvoiceSeries.TAX_INVOICE, true, "Tax Invoice"),
    /** Composition/exempt supply — no GST charged, a "Bill of Supply" per Rule 49. */
    BILL_OF_SUPPLY(InvoiceSeries.BILL_OF_SUPPLY, false, "Bill of Supply"),
    /** Non-GST / unregistered agency — a plain money invoice with no tax lines. */
    SIMPLE_INVOICE(InvoiceSeries.SIMPLE_INVOICE, false, "Invoice");

    private final InvoiceSeries series;
    private final boolean appliesGst;
    private final String title;

    InvoiceType(InvoiceSeries series, boolean appliesGst, String title) {
        this.series = series;
        this.appliesGst = appliesGst;
        this.title = title;
    }

    public InvoiceSeries series() { return series; }
    public boolean appliesGst()   { return appliesGst; }
    public String title()         { return title; }
}