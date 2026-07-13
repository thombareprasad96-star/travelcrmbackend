package com.crm.travelcrm.booking.cancellation.enums;

/**
 * A persisted, immutable cancellation document. Each type has its own gap-free per-tenant number
 * series so an accountant can reconcile credits and debits separately.
 */
public enum DocumentType {

    /** Accounting reversal issued when the cancellation refunds the customer (refundDue &gt;= 0). */
    CREDIT_NOTE("CN", "Credit Note"),

    /** Supplementary/debit instrument issued when the customer still OWES after retention (refundDue &lt; 0). */
    DEBIT_NOTE("DBN", "Debit Note"),

    /** Disbursement acknowledgement issued when a refund is actually paid out. */
    REFUND_VOUCHER("RFV", "Refund Voucher");

    private final String seriesCode;
    private final String title;

    DocumentType(String seriesCode, String title) {
        this.seriesCode = seriesCode;
        this.title = title;
    }

    /** Short prefix for the number series, e.g. "CN" → CN-26-0001. */
    public String seriesCode() { return seriesCode; }

    /** Human title printed on the document. */
    public String title() { return title; }
}
