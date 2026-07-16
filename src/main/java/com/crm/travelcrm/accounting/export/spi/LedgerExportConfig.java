package com.crm.travelcrm.accounting.export.spi;

import com.crm.travelcrm.accounting.invoice.repository.TaxInvoiceRepository;
import com.crm.travelcrm.accounting.tds.repository.VendorBillRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the default {@link LedgerExportProvider} (CSV). Registered as a {@code @Bean} so
 * {@code @ConditionalOnMissingBean} is evaluated in the configuration phase (same rationale as
 * {@code PortalPaymentConfig}); a real Tally/Zoho provider bean supersedes it with no other change.
 */
@Configuration
public class LedgerExportConfig {

    @Bean
    @ConditionalOnMissingBean(LedgerExportProvider.class)
    public LedgerExportProvider csvLedgerExportProvider(TaxInvoiceRepository invoiceRepository,
                                                        VendorBillRepository vendorBillRepository) {
        return new CsvLedgerExportProvider(invoiceRepository, vendorBillRepository);
    }
}