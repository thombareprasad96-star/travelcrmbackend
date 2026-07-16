package com.crm.travelcrm.booking.dto.request;

import com.crm.travelcrm.accounting.invoice.enums.InvoiceType;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Booking-scoped request to generate the <em>accounting</em> GST invoice for a booking. The booking
 * is taken from the path (never the body), so this cannot spoof another booking. It maps onto the
 * accounting {@code IssueInvoiceRequest} in {@code BookingGstInvoiceService} — the very same invoice
 * the accountant issues from the Accounting module, so the two always match.
 */
@Getter
@Setter
public class BookingIssueInvoiceRequest {

    /** Tax Invoice / Bill of Supply / Simple Invoice. Null → the tenant GST scheme's default. */
    private InvoiceType invoiceType;

    /** Invoice date (drives the FY + number series). Null → today. */
    private LocalDate invoiceDate;

    /** Overseas tour package → 206C(1G) TCS is applied (subject to the tenant's auto-TCS setting). */
    private Boolean overseasTourPackage;

    /** Override the recipient GSTIN (B2B). Null → taken from the customer, else B2C. */
    private String recipientGstin;

    /** Override the place-of-supply state (2-digit code or name). Null → derived from the customer. */
    private String placeOfSupplyState;
}