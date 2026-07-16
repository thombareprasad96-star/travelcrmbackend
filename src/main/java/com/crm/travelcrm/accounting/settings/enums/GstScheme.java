package com.crm.travelcrm.accounting.settings.enums;

import com.crm.travelcrm.accounting.invoice.enums.InvoiceSeries;
import com.crm.travelcrm.accounting.invoice.enums.InvoiceType;

/**
 * How the tenant is registered under GST — the single switch that decides whether a booking can be
 * billed as a Tax Invoice at all. This is the backing for the "do we need a GST invoice?" choice: an
 * UNREGISTERED agency simply cannot issue one.
 */
public enum GstScheme {

    /** Regular registered dealer — issues Tax Invoices with CGST/SGST or IGST. */
    REGULAR(InvoiceSeries.TAX_INVOICE, true),
    /** Composition dealer — issues a Bill of Supply (no tax collected from the customer). */
    COMPOSITION(InvoiceSeries.BILL_OF_SUPPLY, false),
    /** Not GST-registered — issues a plain, non-GST invoice only. */
    UNREGISTERED(InvoiceSeries.SIMPLE_INVOICE, false);

    private final InvoiceSeries defaultSeries;
    private final boolean chargesGst;

    GstScheme(InvoiceSeries defaultSeries, boolean chargesGst) {
        this.defaultSeries = defaultSeries;
        this.chargesGst = chargesGst;
    }

    /** True only for REGULAR — the only scheme that may raise a GST Tax Invoice. */
    public boolean canIssueTaxInvoice() {
        return this == REGULAR;
    }

    /** Whether tax is charged to the customer under this scheme. */
    public boolean chargesGst() {
        return chargesGst;
    }

    /** The document book this scheme defaults to when no explicit type is chosen at issue time. */
    public InvoiceSeries defaultSeries() {
        return defaultSeries;
    }

    /** The invoice type this scheme issues by default when the accountant doesn't pick one explicitly. */
    public InvoiceType defaultInvoiceType() {
        return switch (this) {
            case REGULAR -> InvoiceType.TAX_INVOICE;
            case COMPOSITION -> InvoiceType.BILL_OF_SUPPLY;
            case UNREGISTERED -> InvoiceType.SIMPLE_INVOICE;
        };
    }
}