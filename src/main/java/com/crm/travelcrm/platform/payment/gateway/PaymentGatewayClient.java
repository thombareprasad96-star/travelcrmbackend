package com.crm.travelcrm.platform.payment.gateway;

import java.util.Map;

/**
 * SPI for the SaaS-billing payment gateway. One implementation is active at a time:
 * {@link RazorpayGatewayClient} when {@code app.razorpay.enabled=true}, else the
 * {@link UnavailablePaymentGatewayClient} fallback (mirrors the {@code portal.payment} stub pattern).
 *
 * <p>Swapping providers (Stripe/PayU/…) means dropping in a new implementation bean — nothing else
 * in the billing/webhook layer changes.
 */
public interface PaymentGatewayClient {

    /** True when a real, configured gateway is wired (checkout may proceed). */
    boolean isEnabled();

    /** Provider code stored on {@code PaymentTransaction} (e.g. {@code "RAZORPAY"}). */
    String provider();

    /** Public key id handed to the browser Checkout widget; {@code null} when unavailable. */
    String publicKeyId();

    /**
     * Create a payment order for {@code amountMinor} (minor units — paise). {@code notes} are
     * echoed back on webhooks for traceability. Throws {@code BusinessException} when the gateway
     * is unavailable or rejects the request.
     */
    GatewayOrder createOrder(long amountMinor, String currency, String receipt, Map<String, String> notes);

    /** Verify an inbound webhook: HMAC-SHA256 of the exact raw body under the webhook secret. */
    boolean verifyWebhookSignature(byte[] rawBody, String signatureHeader);

    /** Verify a Checkout success callback: HMAC-SHA256 of {@code orderId|paymentId} under the key secret. */
    boolean verifyPaymentSignature(String orderId, String paymentId, String signature);
}
