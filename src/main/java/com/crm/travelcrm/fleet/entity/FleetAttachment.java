package com.crm.travelcrm.fleet.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.fleet.enums.FleetAttachmentOwner;
import jakarta.persistence.*;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * A file attached to fleet money or compliance — receipt image, certificate scan, or the
 * photographed signed settlement sheet.
 *
 * <p><b>Postgres {@code bytea}, never Cloudinary.</b> Same rule and same shape as
 * {@code TravelerDocument}: these are financial and identity papers, Cloudinary URLs are public
 * and unauthenticated, so the bytes live in the DB and are served only through the authenticated,
 * tenant-scoped download endpoint. List reads go through a projection so the blob is never loaded
 * for anything but an actual download.
 *
 * <p><b>Exactly one owner</b> — mirrors {@code FleetComplianceDocument}'s owner CHECK. The three
 * owner FKs are plain columns (real FK constraints live in the DDL); the service resolves and
 * tenant-checks the owner before stamping one.
 *
 * <p><b>Evidence is append-only once money is signed.</b> Upload is always allowed — a late
 * receipt surfacing after the sheet was signed is normal life, and MORE evidence never hurts an
 * audit. Delete is refused the moment the owning expense stops being editable or the settlement is
 * signed: removing evidence from a signed sheet is the fraud vector. {@code sha256} is stamped at
 * upload so any later tampering with the bytes is detectable.
 *
 * <p><b>Retention:</b> deliberately NOT registered in {@code TrashableType} — fleet money evidence
 * follows the 8-year retention rule, so nothing may auto-purge it (see
 * {@code docs/FLEET_MODULE_REDESIGN.md} §4.7). Soft-delete only ever hides a row.
 */
@Entity
@Table(
        name = "fleet_attachments",
        indexes = {
                @Index(name = "idx_fleet_att_tenant",     columnList = "tenant_id"),
                @Index(name = "idx_fleet_att_expense",    columnList = "expense_id"),
                @Index(name = "idx_fleet_att_document",   columnList = "document_id"),
                @Index(name = "idx_fleet_att_settlement", columnList = "settlement_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class FleetAttachment extends BaseTenantEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false, length = 20)
    private FleetAttachmentOwner ownerType;

    /** Logical FK → fleet_expenses.id when {@code ownerType == EXPENSE}. */
    @Column(name = "expense_id")
    private Long expenseId;

    /** Logical FK → fleet_compliance_documents.id when {@code ownerType == DOCUMENT}. */
    @Column(name = "document_id")
    private Long documentId;

    /** Logical FK → fleet_trip_settlements.id when {@code ownerType == SETTLEMENT}. */
    @Column(name = "settlement_id")
    private Long settlementId;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "content_type", length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** SHA-256 of the bytes, hex — stamped at upload, verified never rewritten. */
    @Column(name = "sha256", length = 64)
    private String sha256;

    /** Raw bytes as Postgres {@code bytea}. Excluded from every list read via projection. */
    @Column(name = "content")
    private byte[] content;
}
