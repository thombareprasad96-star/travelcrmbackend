package com.crm.travelcrm.booking.cancellation.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The frozen, customer-safe inputs a cancellation document (credit/debit note or refund voucher) is
 * rendered from. Deliberately flat and Jackson round-trippable so it can be serialised into
 * {@code BookingDocument.modelSnapshotJson} and reproduce the exact document later — branding
 * included, so a reissue can never drift from live company data.
 *
 * <p><b>Customer-safe by construction:</b> it carries NO {@code vendorCost}, {@code netProfit},
 * {@code sunkVendorCost} or any agency-internal figure. The vendor-full {@code BookingCancellation}
 * entity is never handed to a template or serialised here.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CancellationDocumentModel {

    private String documentTitle;     // "Credit Note" | "Debit Note" | "Refund Voucher"
    private String docNumber;         // CN-26-0001 …
    private String originalInvoiceNo; // the booking code the note reverses
    private LocalDate issuedOn;

    // ── Booking / trip ────────────────────────────────────────────────────────
    private String bookingCode;
    private LocalDate bookingDate;
    private LocalDate travelDate;
    private String destination;
    private LocalDate cancelledOn;
    private String reason;

    // ── Bill-to ───────────────────────────────────────────────────────────────
    private String customerName;
    private String customerCode;
    private String customerPhone;
    private String customerEmail;
    private String customerAddress;
    private String customerPan;

    // ── Retained charge (what the agency kept) ────────────────────────────────
    private BigDecimal originalTotalPayable;
    private BigDecimal baseConsidered;
    private BigDecimal chargeBase;
    private BigDecimal gstOnCharge;
    private BigDecimal tcsRetained;
    private BigDecimal totalRetained;

    // ── Settlement ────────────────────────────────────────────────────────────
    private BigDecimal paidAmount;
    /** Signed refund figure for reference. */
    private BigDecimal refundDue;
    private BigDecimal refundToCustomer;
    private BigDecimal customerBalanceOwed;
    private boolean customerOwes;

    // ── Refund-voucher only (disbursement acknowledgement) ────────────────────
    private BigDecimal refundAmount;
    private String refundMethod;
    private String refundReference;
    private LocalDate refundDate;
    private BigDecimal totalRefundedToDate;

    // ── Branding (frozen at issue) ────────────────────────────────────────────
    private String companyName;
    private String companyLogoUrl;
    private String companyTagline;
    private String companyPhone;
    private String companyEmail;
    private String companyWebsite;
    private String companyAddress;
    private String companyGst;
    private String brandColor;
}
