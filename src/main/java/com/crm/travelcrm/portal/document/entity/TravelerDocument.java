package com.crm.travelcrm.portal.document.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.LocalDate;

/**
 * A document a traveler uploaded (passport, visa, photo, …). <b>PII handling:</b> the file bytes are
 * stored in the DB ({@code bytea}, the {@code content} column) — never pushed to a public,
 * world-readable CDN URL. Retrieval is only ever through the ownership-checked
 * {@code GET /api/portal/documents/{publicId}/file} endpoint, which streams the bytes after
 * verifying the document belongs to the requesting traveler's own customer.
 *
 * <p>List and the expiry-reminder job read via a projection ({@code TravelerDocumentView}) so the
 * blob is never loaded for anything but an actual download. Tenant-scoped + soft-deletable via
 * {@link BaseTenantEntity} (consistent with the Trash convention).</p>
 */
@Entity
@Table(
        name = "traveler_documents",
        indexes = {
                @Index(name = "idx_trvdoc_tenant",   columnList = "tenant_id"),
                @Index(name = "idx_trvdoc_customer", columnList = "customer_id"),
                @Index(name = "idx_trvdoc_expiry",   columnList = "tenant_id,expiry_date")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class TravelerDocument extends BaseTenantEntity {

    /** Owner — logical FK → customers.id (same tenant). The object-level ownership key. */
    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 20)
    private TravelerDocumentType type;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "size_bytes")
    private long sizeBytes;

    /** Raw bytes as Postgres {@code bytea}. Excluded from list/job reads via projection. */
    @Column(name = "content")
    private byte[] content;

    /** Optional — PASSPORT/VISA carry an expiry that drives the reminder job; PHOTO/OTHER may not. */
    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 20)
    @Builder.Default
    private DocumentVerificationStatus verificationStatus = DocumentVerificationStatus.PENDING;

    /**
     * Idempotency marker for expiry reminders: the smallest day-threshold already notified (e.g. 30).
     * The job only fires a threshold strictly smaller than this, so each of 60/30/7 fires at most once.
     */
    @Column(name = "last_reminder_day_threshold")
    private Integer lastReminderDayThreshold;
}
