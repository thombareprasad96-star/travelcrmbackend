package com.crm.travelcrm.platform.payment.gateway;

/**
 * Provider-agnostic result of creating a payment order (Razorpay "order", Stripe "PaymentIntent", …).
 * {@code amountMinor} is in the currency's minor unit (paise for INR).
 */
public record GatewayOrder(
        String orderId,
        long amountMinor,
        String currency,
        String receipt,
        String status) {
}
