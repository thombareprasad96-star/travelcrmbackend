package com.crm.travelcrm.accounting.export.spi;

import java.time.LocalDate;

/**
 * SPI for exporting the tenant's accounting ledger (sales invoices + vendor bills) to an external
 * accounting system. The default {@link CsvLedgerExportProvider} emits a generic CSV; a real Tally XML
 * or Zoho Books API provider bean supersedes it (registered {@code @Primary} or replacing the
 * {@code @ConditionalOnMissingBean} default) with no controller change — the same stub-SPI shape used
 * by the payment gateway and e-invoice provider.
 */
public interface LedgerExportProvider {

    /** @param format e.g. "CSV", "TALLY_XML", "ZOHO" — providers advertise what they support. */
    LedgerExport export(Long tenantId, LocalDate from, LocalDate to, String format);
}