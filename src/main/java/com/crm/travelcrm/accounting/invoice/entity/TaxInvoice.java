package com.crm.travelcrm.accounting.invoice.entity;

import com.crm.travelcrm.accounting.invoice.enums.InvoiceStatus;
import com.crm.travelcrm.accounting.invoice.enums.InvoiceType;
import com.crm.travelcrm.accounting.tax.enums.SupplyType;
import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.hibernate.envers.Audited;
import org.hibernate.envers.NotAudited;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An issued invoice — the tenant's statutory book of record. It is a parallel, append-only snapshot of
 * a booking's billed figures at issue time and never mutates back into the {@code Booking}. The
 * {@code invoiceNumber} is minted from a gap-free per-tenant, per-FY series; the frozen
 * {@code modelSnapshotJson} guarantees a reprint reproduces byte-identical statutory content (as with
 * cancellation credit notes). {@code @Audited} so every field change is retained by Envers.
 *
 * <p>Cancellation is a void: {@link #status} flips to CANCELLED but the number and figures are kept.</p>
 */
@Audited
@Entity
@Table(
        name = "tax_invoices",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_tax_invoice_number", columnNames = {"tenant_id", "invoice_number"}),
        indexes = {
                @Index(name = "idx_taxinv_tenant",   columnList = "tenant_id"),
                @Index(name = "idx_taxinv_booking",  columnList = "tenant_id,booking_id"),
                @Index(name = "idx_taxinv_date",     columnList = "tenant_id,invoice_date"),
                @Index(name = "idx_taxinv_status",   columnList = "tenant_id,status"),
                @Index(name = "idx_taxinv_deleted",  columnList = "tenant_id,deleted_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class TaxInvoice extends BaseTenantEntity {

    // ── Identity ──────────────────────────────────────────────────────────────
    @Column(name = "invoice_number", nullable = false, length = 20, updatable = false)
    private String invoiceNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "invoice_type", nullable = false, length = 20, updatable = false)
    private InvoiceType invoiceType;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InvoiceStatus status = InvoiceStatus.ISSUED;

    @Column(name = "invoice_date", nullable = false, updatable = false)
    private LocalDate invoiceDate;

    @Column(name = "financial_year", nullable = false, length = 9, updatable = false)
    private String financialYear;

    // ── Source booking (snapshot) ─────────────────────────────────────────────
    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    @Column(name = "booking_public_id")
    private UUID bookingPublicId;

    @Column(name = "booking_code", length = 20)
    private String bookingCode;

    // ── Supplier (agency) ─────────────────────────────────────────────────────
    @Column(name = "supplier_legal_name", length = 255)
    private String supplierLegalName;

    @Column(name = "supplier_gstin", length = 15)
    private String supplierGstin;

    @Column(name = "supplier_state_code", length = 2)
    private String supplierStateCode;

    // ── Recipient (customer) ──────────────────────────────────────────────────
    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "recipient_name", length = 255)
    private String recipientName;

    @Column(name = "recipient_gstin", length = 15)
    private String recipientGstin;

    @Column(name = "recipient_state_code", length = 2)
    private String recipientStateCode;

    // ── Supply classification ─────────────────────────────────────────────────
    @Column(name = "place_of_supply_code", length = 2)
    private String placeOfSupplyCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "supply_type", length = 15)
    private SupplyType supplyType;

    @Builder.Default
    @Column(name = "reverse_charge", nullable = false)
    private boolean reverseCharge = false;

    @Builder.Default
    @Column(name = "overseas_tour_package", nullable = false)
    private boolean overseasTourPackage = false;

    // ── Money (BigDecimal 14,2) ───────────────────────────────────────────────
    @Builder.Default
    @Column(name = "taxable_value", nullable = false, precision = 14, scale = 2)
    private BigDecimal taxableValue = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "cgst", nullable = false, precision = 14, scale = 2)
    private BigDecimal cgst = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "sgst", nullable = false, precision = 14, scale = 2)
    private BigDecimal sgst = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "igst", nullable = false, precision = 14, scale = 2)
    private BigDecimal igst = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "cess", nullable = false, precision = 14, scale = 2)
    private BigDecimal cess = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "tcs", nullable = false, precision = 14, scale = 2)
    private BigDecimal tcs = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "round_off", nullable = false, precision = 14, scale = 2)
    private BigDecimal roundOff = BigDecimal.ZERO;

    @Builder.Default
    @Column(name = "invoice_total", nullable = false, precision = 14, scale = 2)
    private BigDecimal invoiceTotal = BigDecimal.ZERO;

    /** GST on ITC-eligible lines (input credit the tenant may claim) — drives the P&L cost netting. */
    @Builder.Default
    @Column(name = "itc_creditable", nullable = false, precision = 14, scale = 2)
    private BigDecimal itcCreditable = BigDecimal.ZERO;

    // ── Frozen document ───────────────────────────────────────────────────────
    @NotAudited
    @Column(name = "model_snapshot_json", columnDefinition = "TEXT")
    private String modelSnapshotJson;

    /** Frozen PDF bytes (Postgres {@code bytea}); null until first render, immutable thereafter. */
    @NotAudited
    @Column(name = "pdf_bytes")
    private byte[] pdfBytes;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "issued_by_email", length = 255)
    private String issuedByEmail;

    // ── Void ──────────────────────────────────────────────────────────────────
    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "cancel_reason", length = 500)
    private String cancelReason;

    /** GST credit note number issued against this invoice on void, if any. */
    @Column(name = "credit_note_number", length = 20)
    private String creditNoteNumber;

    // ── e-invoice (IRN) — filled by the EInvoiceProvider when a real IRP is wired ─
    @Builder.Default
    @Column(name = "einvoice_status", length = 20)
    private String einvoiceStatus = "NOT_APPLICABLE";

    @Column(name = "irn", length = 64)
    private String irn;

    @Column(name = "irn_ack_no", length = 40)
    private String irnAckNo;

    @Column(name = "irn_ack_date")
    private LocalDateTime irnAckDate;

    @NotAudited
    @Column(name = "signed_qr_data", columnDefinition = "TEXT")
    private String signedQrData;
}