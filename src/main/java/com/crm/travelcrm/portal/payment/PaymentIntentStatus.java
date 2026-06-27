package com.crm.travelcrm.portal.payment;

/** Outcome of a portal pay request. */
public enum PaymentIntentStatus {
    /** A provider intent was created — client should proceed to {@code redirectUrl}/SDK. */
    INITIATED,
    /** Accepted, awaiting provider confirmation (webhook). */
    PENDING,
    /** No payment provider is wired yet — the contract is live, the integration is a hook. */
    UNAVAILABLE
}
