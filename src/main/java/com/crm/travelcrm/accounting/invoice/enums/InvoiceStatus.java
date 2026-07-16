package com.crm.travelcrm.accounting.invoice.enums;

/**
 * Lifecycle of an issued invoice. An invoice number is never reused: once issued it is either ISSUED
 * or, if voided, CANCELLED (the number is retained and reported as cancelled — never soft-deleted and
 * reclaimed), preserving the gap-free statutory series.
 */
public enum InvoiceStatus {
    ISSUED,
    CANCELLED
}