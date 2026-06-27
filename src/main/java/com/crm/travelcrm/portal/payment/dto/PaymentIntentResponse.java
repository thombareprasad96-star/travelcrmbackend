package com.crm.travelcrm.portal.payment.dto;

import com.crm.travelcrm.portal.payment.PaymentIntentStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Portal pay response contract. When a real Payments module is wired it carries
 * {@code status=INITIATED} + {@code redirectUrl}/{@code providerRef}; the current stub returns
 * {@code UNAVAILABLE}. The FE builds against this shape today.
 */
@Data
@Builder
public class PaymentIntentResponse {
    private UUID bookingPublicId;
    private BigDecimal amount;
    private String currency;
    private PaymentIntentStatus status;
    private String providerRef;   // provider intent id (nullable)
    private String redirectUrl;   // hosted checkout / SDK URL (nullable)
    private String message;
}
