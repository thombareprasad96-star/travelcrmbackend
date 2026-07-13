package com.crm.travelcrm.booking.cancellation.entity;

import com.crm.travelcrm.booking.cancellation.enums.DocumentType;
import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDateTime;

/**
 * An immutable, persisted cancellation document (credit note, debit note, or refund voucher). Unlike
 * the live-rendered booking invoice/voucher, these are legal/accounting instruments that MUST
 * reproduce byte-identically on every reprint — so both the frozen input snapshot and, once rendered,
 * the exact PDF bytes are stored here and never recomputed.
 *
 * <p>{@link #docNumber} is minted from a gap-free per-tenant series inside the cancellation
 * transaction (so it is reserved atomically with the money). {@link #pdfBytes} are rendered and
 * frozen lazily on first fetch — NOT inside the cancel transaction — so the pessimistic number-series
 * lock is never held across a slow PDF render.
 */
@Entity
@Table(
        name = "booking_documents",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_booking_document_number", columnNames = {"tenant_id", "doc_number"}),
        indexes = {
                @Index(name = "idx_bkdoc_tenant",  columnList = "tenant_id"),
                @Index(name = "idx_bkdoc_booking", columnList = "tenant_id,booking_id,doc_type"),
                @Index(name = "idx_bkdoc_deleted", columnList = "tenant_id,deleted_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class BookingDocument extends BaseTenantEntity {

    /** Owning booking — logical FK to {@code bookings.id}. */
    @Column(name = "booking_id", nullable = false)
    private Long bookingId;

    /** The cancellation this document belongs to — logical FK to {@code booking_cancellations.id}. */
    @Column(name = "cancellation_id")
    private Long cancellationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 20)
    private DocumentType docType;

    /** Gap-free per-tenant series number, e.g. CN-26-0001. */
    @Column(name = "doc_number", nullable = false, length = 30)
    private String docNumber;

    /**
     * The frozen, customer-safe model the PDF is rendered from (JSON), including the branding as-of
     * issue. This is the reproducibility record: if the bytes are ever lost, the document regenerates
     * from this exact snapshot — never from live booking/company data.
     */
    @Column(name = "model_snapshot_json", columnDefinition = "TEXT")
    private String modelSnapshotJson;

    /** Frozen PDF bytes (Postgres {@code bytea}); null until first render, immutable thereafter. */
    @Column(name = "pdf_bytes")
    private byte[] pdfBytes;

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "issued_by_email", length = 255)
    private String issuedByEmail;
}
