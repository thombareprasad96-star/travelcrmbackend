package com.crm.travelcrm.platform.payment.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Everything the browser Razorpay Checkout widget needs to open for a specific invoice. The
 * {@code gatewayKeyId} is the PUBLIC key id (safe to expose); the secret never leaves the server.
 * {@code amountMinor} (paise) is what Checkout expects; {@code amount} is the rupee value for display.
 */
@Value
@Builder
public class PaymentOrderResponse {

    UUID transactionPublicId;
    String provider;
    String gatewayKeyId;
    String orderId;
    long amountMinor;
    BigDecimal amount;
    String currency;

    UUID invoicePublicId;
    String invoiceNumber;

    String tenantName;
    String prefillEmail;
    String prefillContact;
}