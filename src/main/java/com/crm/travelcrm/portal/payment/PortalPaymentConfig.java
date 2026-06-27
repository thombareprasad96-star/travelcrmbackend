package com.crm.travelcrm.portal.payment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the default {@link PortalPaymentInitiation}.
 *
 * <p>The stub is registered as a {@code @Bean} (not a {@code @Component}) on purpose:
 * {@code @ConditionalOnMissingBean} is only reliable in the configuration phase. Placed here it
 * registers the {@link StubPortalPaymentInitiation} fallback when no other implementation exists,
 * and cleanly backs off the moment a real payment-gateway bean of type {@link PortalPaymentInitiation}
 * is added — with no changes to the rest of the portal.</p>
 */
@Configuration
public class PortalPaymentConfig {

    @Bean
    @ConditionalOnMissingBean(PortalPaymentInitiation.class)
    public PortalPaymentInitiation stubPortalPaymentInitiation() {
        return new StubPortalPaymentInitiation();
    }
}