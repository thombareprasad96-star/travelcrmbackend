package com.crm.travelcrm.platform.billing.enums;

/**
 * Payment state of a platform invoice ({@code BillingRecord}). "Overdue" is intentionally NOT a
 * status — it is derived (UNPAID + past due date) so there is no scheduler to keep in sync.
 */
public enum BillingStatus {
    UNPAID,
    PAID,
    VOID,
    /**
     * A credit note — a negative-amount {@code BillingRecord} raised when a mid-cycle DOWNGRADE
     * prorates money back to the tenant. Not "owed" (excluded from the unpaid/outstanding totals);
     * carried on the account and applied against a future invoice.
     */
    CREDIT
}