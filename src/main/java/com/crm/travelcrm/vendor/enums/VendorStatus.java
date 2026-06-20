package com.crm.travelcrm.vendor.enums;

/**
 * Lifecycle status of a {@code Vendor}. Persisted via {@code @Enumerated(EnumType.STRING)}.
 * Legacy free-text values ("Active", "Inactive", "Blacklisted") are normalized to these
 * names by the startup SQL init script (see {@code db/indexes.sql}).
 */
public enum VendorStatus {
    ACTIVE,
    INACTIVE,
    SUSPENDED,
    BLACKLISTED
}