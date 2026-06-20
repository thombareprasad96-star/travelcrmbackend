package com.crm.travelcrm.vendor.enums;

/**
 * Payment standing of a {@code Vendor}. Persisted via {@code @Enumerated(EnumType.STRING)}.
 * Legacy free-text values ("Paid", "Unpaid") are normalized to these names by the startup
 * SQL init script (see {@code db/indexes.sql}).
 */
public enum VendorPayStatus {
    PAID,
    UNPAID,
    PARTIALLY_PAID,
    OVERDUE
}