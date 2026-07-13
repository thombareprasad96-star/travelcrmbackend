package com.crm.travelcrm.platform.subscription.upgrade.enums;

/**
 * How the tenant pays for a plan {@code UpgradeRequest}.
 *
 * <ul>
 *   <li>{@code ONLINE} — pay-per-invoice via the existing Razorpay flow; the linked invoice is
 *       settled by the {@code payment.captured} webhook before approval.</li>
 *   <li>{@code OFFLINE} — bank transfer / cheque / cash; the tenant supplies a reference and the
 *       SuperAdmin marks the linked invoice paid at approval time.</li>
 * </ul>
 */
public enum PaymentMode {
    ONLINE,
    OFFLINE
}