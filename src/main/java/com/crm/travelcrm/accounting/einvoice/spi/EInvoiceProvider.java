package com.crm.travelcrm.accounting.einvoice.spi;

import com.crm.travelcrm.accounting.invoice.entity.TaxInvoice;

/**
 * SPI for e-invoice (IRN) generation against the government Invoice Registration Portal. The default
 * bean is a no-op stub ({@link UnavailableEInvoiceProvider}) returning {@link IrnResult#unavailable()};
 * dropping in a real NIC/GSP-backed {@code @Primary} bean enables IRN/QR with no changes elsewhere —
 * the frozen invoice model and gap-free number are the only prerequisites, and both already exist.
 */
public interface EInvoiceProvider {

    IrnResult generate(TaxInvoice invoice);
}