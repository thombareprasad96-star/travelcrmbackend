package com.crm.travelcrm.booking.dto.pdf;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything the invoice / voucher Thymeleaf templates need, assembled server-side.
 *
 * <p>Deliberately customer-safe: it carries NO {@code vendorCost} and NO {@code netProfit}.
 * Only customer-facing figures (amount, GST, TCS, total, paid, pending) and operational
 * confirmation data appear here, so a template can never leak agency margins.</p>
 */
@Getter
@Builder
public class BookingDocumentModel {

    /** "INVOICE" or "VOUCHER" — lets a shared fragment vary a heading if needed. */
    private final String documentType;

    // ── Booking ──────────────────────────────────────────────────────────────
    private final String    bookingCode;
    private final String    status;
    private final String    paymentStatus;
    private final LocalDate  bookingDate;
    private final LocalDate  travelDate;
    private final String     destination;
    private final String     createdBy;
    private final LocalDate  generatedOn;

    /**
     * Owning user's internal id — a server-only carrier for white-label PDF branding: when the
     * booking is owned by an active sub-agent, their brand overrides the tenant Company's on the
     * invoice/voucher. Never rendered into the document text itself.
     */
    private final Long       ownerUserId;

    // ── Bill-to / traveller ──────────────────────────────────────────────────
    private final Party customer;

    // ── Financials (customer-facing only) ────────────────────────────────────
    private final BigDecimal customerAmount;
    private final BigDecimal gst;
    private final BigDecimal tcs;
    private final BigDecimal totalPayable;
    private final BigDecimal paidAmount;
    private final BigDecimal pendingAmount;
    private final String     gstRatePct;   // e.g. "5"
    private final String     tcsRatePct;   // e.g. "5"

    // ── Line items & receipts ────────────────────────────────────────────────
    private final List<Line>    services;
    private final List<Receipt> payments;

    @Getter
    @Builder
    public static class Party {
        private final String name;
        private final String code;
        private final String phone;
        private final String email;
        private final String address;
        private final String city;
        private final String state;
        private final String pincode;
        private final String panNo;
    }

    @Getter
    @Builder
    public static class Line {
        private final String     serviceType;
        private final String     title;
        private final String     description;
        private final LocalDate  serviceDate;
        private final LocalDate  endDate;
        private final String     status;
        private final BigDecimal cost;
        private final String     vendorName;
        private final String     confirmationNumber;
    }

    @Getter
    @Builder
    public static class Receipt {
        private final LocalDate  paymentDate;
        private final BigDecimal amount;
        private final String     method;
        private final String     reference;
        private final String     paymentType;
    }
}