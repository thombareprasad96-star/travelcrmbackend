package com.crm.travelcrm.platform.payment.gateway;

import com.crm.travelcrm.platform.payment.config.RazorpayProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires exactly one {@link PaymentGatewayClient}. When {@code app.razorpay.enabled=true} the real
 * {@link RazorpayGatewayClient} is registered; otherwise the {@link UnavailablePaymentGatewayClient}
 * fallback is (via {@code @ConditionalOnMissingBean}). Declaring the real bean FIRST makes the
 * missing-bean evaluation deterministic — no component-scan ordering to reason about.
 */
@Configuration
@EnableConfigurationProperties(RazorpayProperties.class)
public class PaymentGatewayConfig {

    @Bean
    @ConditionalOnProperty(prefix = "app.razorpay", name = "enabled", havingValue = "true")
    public PaymentGatewayClient razorpayGatewayClient(RazorpayProperties props, ObjectMapper mapper) {
        return new RazorpayGatewayClient(props, mapper);
    }

    @Bean
    @ConditionalOnMissingBean(PaymentGatewayClient.class)
    public PaymentGatewayClient unavailablePaymentGatewayClient() {
        return new UnavailablePaymentGatewayClient();
    }
}