package com.crm.travelcrm.platform.payment.enums;

/**
 * Lifecycle of a SaaS {@code PaymentTransaction}. {@code CREATED} = order placed with the gateway,
 * awaiting the customer; {@code CAPTURED} = money received (webhook {@code payment.captured});
 * {@code FAILED} = the attempt failed; {@code REFUNDED} = later reversed.
 */
public enum PaymentTransactionStatus {
    CREATED,
    CAPTURED,
    FAILED,
    REFUNDED
}