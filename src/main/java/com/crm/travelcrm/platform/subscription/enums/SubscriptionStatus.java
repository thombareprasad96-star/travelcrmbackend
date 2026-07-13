package com.crm.travelcrm.platform.subscription.enums;

/**
 * Recurring-subscription lifecycle, aligned to Razorpay's subscription states. Used by the
 * {@code Subscription} scaffold — the Orders/pay-per-invoice flow is the live billing path today;
 * these states are populated once the recurring (auto-debit) flow is switched on.
 */
public enum SubscriptionStatus {
    CREATED,
    AUTHENTICATED,
    ACTIVE,
    PENDING,
    HALTED,
    PAUSED,
    CANCELLED,
    COMPLETED,
    EXPIRED
}