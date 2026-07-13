package com.crm.travelcrm.platform.payment.gateway;

import com.crm.travelcrm.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Fallback gateway used until Razorpay is configured ({@code app.razorpay.enabled=false} / no keys).
 * Never pretends a charge happened: {@link #createOrder} refuses with a friendly 503, and signature
 * checks return {@code false}. Registered by {@link PaymentGatewayConfig} via
 * {@code @ConditionalOnMissingBean}, so a real gateway bean transparently takes over.
 */
@Slf4j
public class UnavailablePaymentGatewayClient implements PaymentGatewayClient {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public String provider() {
        return "NONE";
    }

    @Override
    public String publicKeyId() {
        return null;
    }

    @Override
    public GatewayOrder createOrder(long amountMinor, String currency, String receipt, Map<String, String> notes) {
        log.info("[SAAS-PAY][stub] order requested amount(minor)={} {} receipt={} — no gateway configured",
                amountMinor, currency, receipt);
        throw new BusinessException(
                "Online payment is not enabled yet. Please contact support to settle this invoice.",
                HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Override
    public boolean verifyWebhookSignature(byte[] rawBody, String signatureHeader) {
        return false;
    }

    @Override
    public boolean verifyPaymentSignature(String orderId, String paymentId, String signature) {
        return false;
    }
}