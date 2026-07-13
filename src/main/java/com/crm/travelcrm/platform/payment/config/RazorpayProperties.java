package com.crm.travelcrm.platform.payment.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Razorpay wiring for the <b>SaaS billing</b> gateway (platform → tenant subscription payments).
 * Distinct from the customer-facing {@code portal.payment} booking-payment SPI.
 *
 * <p>The gateway stays OFF ({@link #enabled} = false) until real credentials are configured, so the
 * app boots and the {@code /api/company/billing/**} + webhook endpoints exist without keys — the
 * {@code UnavailablePaymentGatewayClient} fallback answers until a real key is set. Set the secrets
 * via env vars in prod; never commit live keys.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.razorpay")
public class RazorpayProperties {

    /** Master switch. When true the real {@code RazorpayGatewayClient} bean is wired. */
    private boolean enabled = false;

    /** Public key id (rzp_test_… / rzp_live_…) — safe to hand to the browser Checkout. */
    private String keyId;

    /** Secret key — used for HTTP Basic auth on the Orders API and payment-signature verification. */
    private String keySecret;

    /** Webhook signing secret (set in the Razorpay dashboard) — verifies inbound webhook HMACs. */
    private String webhookSecret;

    /** Default settlement currency. */
    private String currency = "INR";

    /** Razorpay REST base (no trailing slash). */
    private String apiBaseUrl = "https://api.razorpay.com/v1";

    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 10000;
}
