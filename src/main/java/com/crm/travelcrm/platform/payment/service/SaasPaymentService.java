package com.crm.travelcrm.platform.payment.service;

import com.crm.travelcrm.platform.payment.dto.PaymentOrderResponse;

import java.util.UUID;

/** SaaS subscription payments: create a gateway order for an invoice, and process inbound webhooks. */
public interface SaasPaymentService {

    /**
     * Create a payment order so the tenant can pay {@code invoicePublicId} online. Verifies the
     * invoice belongs to {@code tenantId} and is still UNPAID; throws if the gateway is unavailable.
     */
    PaymentOrderResponse createOrderForInvoice(UUID invoicePublicId, Long tenantId);

    /**
     * Handle an inbound Razorpay webhook. Verifies the HMAC signature first (throws on mismatch),
     * is idempotent on the gateway event id, and settles the linked invoice on capture.
     *
     * @param rawBody          exact bytes of the request body (HMAC is computed over these)
     * @param signatureHeader  {@code X-Razorpay-Signature}
     * @param eventIdHeader    {@code X-Razorpay-Event-Id} (may be null; a body hash is used instead)
     */
    void handleRazorpayWebhook(byte[] rawBody, String signatureHeader, String eventIdHeader);
}