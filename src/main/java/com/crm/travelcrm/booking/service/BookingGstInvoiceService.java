package com.crm.travelcrm.booking.service;

import com.crm.travelcrm.accounting.invoice.dto.TaxInvoiceResponse;
import com.crm.travelcrm.booking.dto.request.BookingIssueInvoiceRequest;

import java.util.List;
import java.util.UUID;

/**
 * Bridge from a booking to the <em>accounting</em> GST invoice subsystem. The booking module owns no
 * invoice logic of its own here — it delegates to {@code accounting.InvoiceService} so a booking's
 * invoice is byte-for-byte the same document the accountant issues from the Accounting module.
 *
 * <p>Generation ({@link #issue}/{@link #cancel}) is restricted to tenant-admin + accountant at the
 * controller ({@code ACCOUNTING_INVOICE_MANAGE}); listing/viewing is open to any booking reader
 * ({@code BOOKING_READ}). Every read/mutation re-checks that the invoice actually belongs to the
 * booking in the path, so a foreign invoice id returns 404.
 */
public interface BookingGstInvoiceService {

    /** All GST invoices raised against this booking (most-recent first, per the accounting service). */
    List<TaxInvoiceResponse> list(UUID bookingPublicId, Long tenantId);

    /** Issue the accounting GST invoice for this booking (lines auto-assembled from its service items). */
    TaxInvoiceResponse issue(UUID bookingPublicId, BookingIssueInvoiceRequest request, Long tenantId, String userEmail);

    /** Fetch one invoice, verifying it belongs to this booking (404 otherwise). */
    TaxInvoiceResponse get(UUID bookingPublicId, UUID invoicePublicId, Long tenantId);

    /** Frozen PDF bytes for one of this booking's invoices (ownership-checked). */
    byte[] renderPdf(UUID bookingPublicId, UUID invoicePublicId, Long tenantId);

    /** Cancel one of this booking's invoices (ownership-checked). */
    TaxInvoiceResponse cancel(UUID bookingPublicId, UUID invoicePublicId, String reason, Long tenantId, String userEmail);
}