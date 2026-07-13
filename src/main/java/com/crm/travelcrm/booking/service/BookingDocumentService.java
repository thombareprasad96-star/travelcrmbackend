package com.crm.travelcrm.booking.service;

import java.util.UUID;

/**
 * Produces the on-the-fly PDF documents for a booking. Both are rendered fresh on every request
 * (no caching) so an invoice always reflects the current paid/pending figures.
 */
public interface BookingDocumentService {

    /** GST/TCS tax invoice with the payment ledger. */
    byte[] renderInvoice(UUID bookingPublicId);

    /** Booking confirmation voucher (all service lines). */
    byte[] renderVoucher(UUID bookingPublicId);

    /** Voucher scoped to a single service line (its own confirmation). */
    byte[] renderServiceVoucher(UUID bookingPublicId, UUID serviceItemPublicId);
}