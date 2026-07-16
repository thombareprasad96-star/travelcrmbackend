package com.crm.travelcrm.accounting.invoice.dto.pdf;

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The frozen, Jackson round-trippable inputs a GST invoice PDF renders from. Persisted into
 * {@code TaxInvoice.modelSnapshotJson} at issue time so a reprint reproduces byte-identical statutory
 * content — branding, seller/buyer GSTIN and every figure captured as-of issue, never re-read from
 * live data (the same reproducibility contract as {@code CancellationDocumentModel}).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaxInvoiceModel {

    // ── Header ────────────────────────────────────────────────────────────────
    private String documentTitle;      // "Tax Invoice" | "Bill of Supply" | "Invoice"
    private String invoiceNumber;
    private LocalDate invoiceDate;
    private String financialYear;
    private boolean reverseCharge;
    private boolean appliesGst;
    private boolean intraState;         // true → CGST+SGST columns; false → IGST column
    private String placeOfSupply;       // "Maharashtra (27)"
    private String supplyTypeLabel;

    // ── Supplier (agency) ─────────────────────────────────────────────────────
    private String companyName;
    private String companyTagline;
    private String companyLogoUrl;
    private String companyAddress;
    private String companyPhone;
    private String companyEmail;
    private String companyWebsite;
    private String supplierGstin;
    private String supplierStateLabel;
    private String companyPan;
    private String brandColor;

    // ── Recipient (customer) ──────────────────────────────────────────────────
    private String recipientName;
    private String recipientAddress;
    private String recipientPhone;
    private String recipientEmail;
    private String recipientGstin;
    private String recipientStateLabel;

    // ── Booking / trip ────────────────────────────────────────────────────────
    private String bookingCode;
    private LocalDate bookingDate;
    private LocalDate travelDate;
    private String destination;

    // ── Lines + tax summary ───────────────────────────────────────────────────
    private List<Line> lines;
    private List<TaxSummaryRow> taxSummary;

    // ── Totals ────────────────────────────────────────────────────────────────
    private BigDecimal taxableValue;
    private BigDecimal totalCgst;
    private BigDecimal totalSgst;
    private BigDecimal totalIgst;
    private BigDecimal totalCess;
    private BigDecimal tcs;
    private boolean overseasTourPackage;
    private BigDecimal roundOff;
    private BigDecimal invoiceTotal;
    private String amountInWords;

    // ── e-invoice (optional) ──────────────────────────────────────────────────
    private String irn;
    private String irnAckNo;
    private String signedQrData;

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Line {
        private int lineNo;
        private String description;
        private String hsnSac;
        private BigDecimal taxableValue;
        private BigDecimal gstRatePct;
        private BigDecimal cgstAmt;
        private BigDecimal sgstAmt;
        private BigDecimal igstAmt;
        private BigDecimal cessAmt;
        private BigDecimal lineTotal;
    }

    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TaxSummaryRow {
        private BigDecimal gstRatePct;
        private BigDecimal taxableValue;
        private BigDecimal cgstAmt;
        private BigDecimal sgstAmt;
        private BigDecimal igstAmt;
        private BigDecimal cessAmt;
    }
}