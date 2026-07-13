package com.crm.travelcrm.platform.payment.gateway;

import com.crm.travelcrm.common.exception.BusinessException;
import com.crm.travelcrm.platform.payment.config.RazorpayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * Real Razorpay gateway. Deliberately implemented on the JDK {@link HttpClient} + {@code javax.crypto}
 * rather than the {@code razorpay-java} SDK: this project enforces <b>Log4j2 as the single logging
 * provider</b> (every dependency in the POM has explicit Logback/commons-logging exclusions), and the
 * SDK drags in its own logging/HTTP transitive deps. Order creation is one POST and both signature
 * checks are standard HMAC-SHA256, so no dependency is warranted. Wired only when
 * {@code app.razorpay.enabled=true} (see {@link PaymentGatewayConfig}).
 */
@Slf4j
public class RazorpayGatewayClient implements PaymentGatewayClient {

    private static final String PROVIDER = "RAZORPAY";

    private final RazorpayProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public RazorpayGatewayClient(RazorpayProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()))
                .build();
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public String publicKeyId() {
        return props.getKeyId();
    }

    @Override
    public GatewayOrder createOrder(long amountMinor, String currency, String receipt, Map<String, String> notes) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("amount", amountMinor);
            body.put("currency", currency != null ? currency : props.getCurrency());
            if (receipt != null) {
                body.put("receipt", receipt);
            }
            body.put("payment_capture", true);   // auto-capture on success
            if (notes != null && !notes.isEmpty()) {
                ObjectNode n = body.putObject("notes");
                notes.forEach((k, v) -> {
                    if (v != null) {
                        n.put(k, v);
                    }
                });
            }

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(props.getApiBaseUrl() + "/orders"))
                    .timeout(Duration.ofMillis(props.getReadTimeoutMs()))
                    .header("Authorization", basicAuth())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                log.error("[RAZORPAY] order create failed: HTTP {} body={}", resp.statusCode(), resp.body());
                throw new BusinessException("The payment gateway rejected the request. Please try again.",
                        HttpStatus.BAD_GATEWAY);
            }

            JsonNode json = mapper.readTree(resp.body());
            return new GatewayOrder(
                    json.path("id").asText(null),
                    json.path("amount").asLong(amountMinor),
                    json.path("currency").asText(currency),
                    json.path("receipt").asText(receipt),
                    json.path("status").asText("created"));

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("[RAZORPAY] order create error: {}", e.getMessage(), e);
            throw new BusinessException("Could not reach the payment gateway. Please try again shortly.",
                    HttpStatus.BAD_GATEWAY);
        }
    }

    @Override
    public boolean verifyWebhookSignature(byte[] rawBody, String signatureHeader) {
        if (rawBody == null || signatureHeader == null
                || props.getWebhookSecret() == null || props.getWebhookSecret().isBlank()) {
            return false;
        }
        String expected = hmacSha256Hex(rawBody, props.getWebhookSecret());
        return constantTimeEquals(expected, signatureHeader.trim());
    }

    @Override
    public boolean verifyPaymentSignature(String orderId, String paymentId, String signature) {
        if (orderId == null || paymentId == null || signature == null
                || props.getKeySecret() == null || props.getKeySecret().isBlank()) {
            return false;
        }
        String expected = hmacSha256Hex(
                (orderId + "|" + paymentId).getBytes(StandardCharsets.UTF_8), props.getKeySecret());
        return constantTimeEquals(expected, signature.trim());
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private String basicAuth() {
        String creds = props.getKeyId() + ":" + props.getKeySecret();
        return "Basic " + Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
    }

    private static String hmacSha256Hex(byte[] data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(data);
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 computation failed", e);
        }
    }

    /** Constant-time compare so signature verification cannot be timing-attacked. */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}