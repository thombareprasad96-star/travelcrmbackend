package com.crm.travelcrm.accounting.einvoice.spi;

import com.crm.travelcrm.accounting.invoice.entity.TaxInvoice;

/**
 * Default e-invoice provider — the fallback registered by {@link EInvoiceConfig} via
 * {@code @ConditionalOnMissingBean} (mirrors {@code StubPortalPaymentInitiation} /
 * {@code UnavailablePaymentGatewayClient}). Always reports UNAVAILABLE so IRN generation degrades
 * gracefully; a real IRP/GSP bean of type {@link EInvoiceProvider} supersedes it with no other change.
 */
public class UnavailableEInvoiceProvider implements EInvoiceProvider {

    @Override
    public IrnResult generate(TaxInvoice invoice) {
        return IrnResult.unavailable();
    }
}