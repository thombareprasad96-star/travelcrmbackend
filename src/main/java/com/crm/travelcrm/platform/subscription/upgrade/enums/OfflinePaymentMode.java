package com.crm.travelcrm.platform.subscription.upgrade.enums;

/**
 * The instrument used for an {@code OFFLINE} plan-upgrade payment. Purely descriptive metadata the
 * SuperAdmin verifies against the supplied reference. Stored as {@code STRING}; its
 * {@code upgrade_requests_offline_mode_check} constraint is refreshed in {@code db/indexes.sql}.
 */
public enum OfflinePaymentMode {
    BANK_TRANSFER,
    CHEQUE,
    CASH
}