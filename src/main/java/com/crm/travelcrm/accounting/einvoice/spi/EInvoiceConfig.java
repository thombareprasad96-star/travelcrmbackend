package com.crm.travelcrm.accounting.einvoice.spi;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the default {@link EInvoiceProvider}. The stub is registered as a {@code @Bean} (not a
 * {@code @Component}) on purpose — {@code @ConditionalOnMissingBean} is only reliable in the
 * configuration phase (same rationale as {@code PortalPaymentConfig}). It backs off the moment a real
 * IRP/GSP-backed {@link EInvoiceProvider} bean is registered.
 */
@Configuration
public class EInvoiceConfig {

    @Bean
    @ConditionalOnMissingBean(EInvoiceProvider.class)
    public EInvoiceProvider unavailableEInvoiceProvider() {
        return new UnavailableEInvoiceProvider();
    }
}