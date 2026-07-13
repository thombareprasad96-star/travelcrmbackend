package com.crm.travelcrm.portal.booking.dto;

import lombok.Builder;
import lombok.Data;

/**
 * A downloadable document available to the traveler for one of their bookings. Deliberately tiny:
 * the portal lists only documents that actually exist (the invoice always does), and the frontend
 * builds the download link from {@code path} — appended to {@code /api/portal/bookings/{id}/}.
 *
 * <p>Every referenced document is customer-safe by construction: the invoice/voucher/credit-note
 * PDFs render from whitelisted, customer-facing figures only — no vendor cost or agency margin.
 */
@Data
@Builder
public class TravelerBookingDocumentDto {

    /** Machine key: INVOICE | CREDIT_NOTE | DEBIT_NOTE | REFUND_VOUCHER. */
    private String type;

    /** Human label, e.g. "Tax Invoice", "Credit Note", "Refund Voucher". */
    private String label;

    /** Path suffix to append to {@code /api/portal/bookings/{publicId}/} for the PDF (blob) download. */
    private String path;
}