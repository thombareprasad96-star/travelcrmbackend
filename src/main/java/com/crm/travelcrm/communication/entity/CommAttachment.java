package com.crm.travelcrm.communication.entity;

import com.crm.travelcrm.common.entity.BaseTenantEntity;
import com.crm.travelcrm.common.entity.Ownable;
import com.crm.travelcrm.communication.enums.MessageDirection;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * A file on a message — WhatsApp media, an email attachment, a shared document.
 *
 * <p><b>Bytes live in Postgres, never Cloudinary.</b> Cloudinary URLs are public and
 * unauthenticated; a customer's passport scan, a payment screenshot or an invoice PDF arriving on
 * WhatsApp is exactly the class of data {@code TravelerDocument} keeps in {@code bytea} behind an
 * ownership check. {@code CloudinaryService.deleteImage} also has zero callers, so metered bytes
 * there only ever grow.
 *
 * <p><b>Its own table, not a column on {@code comm_messages}.</b> A {@code byte[]} column is eagerly
 * fetched by default, so a single blob column on the timeline table would drag every attachment
 * into every inbox page load. Keeping it separate means the timeline never touches it, and list
 * reads here use a projection that omits {@link #content} entirely.
 *
 * <p>Quota: {@code StorageQuota.enforceWithinQuota(tenantId, size)} runs BEFORE the bytes are read,
 * and usage is metered by {@code SUM(size_bytes)} rather than a separate meter call — the
 * {@code TravelerDocument} pattern. {@code StorageQuotaGuard}, {@code UsageServiceImpl} and
 * {@code UsageAlertService} must all learn about this fourth byte source together, or the gate
 * blocks at a number the SuperAdmin dashboard cannot see.
 */
@Entity
@Table(name = "comm_attachments", indexes = {
        @Index(name = "idx_catt_tenant",  columnList = "tenant_id"),
        @Index(name = "idx_catt_message", columnList = "message_id"),
        @Index(name = "idx_catt_owner",   columnList = "owner_user_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@SuperBuilder
public class CommAttachment extends BaseTenantEntity implements Ownable {

    /** {@code comm_messages.id}. */
    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "content_type", length = 120)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** SHA-256 of {@link #content}, stamped at write time. Lets a duplicate upload be recognised. */
    @Column(name = "sha256", length = 64)
    private String sha256;

    /**
     * The bytes.
     *
     * <p>Excluded from every list and scheduler read via an interface projection — loading the
     * entity is only ever correct on the single-file download path.
     */
    @Column(name = "content")
    private byte[] content;

    /** Which way the file travelled. Inbound files are fetched from the provider's media URL. */
    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10)
    private MessageDirection direction;

    /**
     * The provider's media handle, kept for inbound files.
     *
     * <p>Diagnostic only. Provider media URLs expire (Meta's are valid for minutes), which is
     * exactly why the bytes are copied into Postgres rather than referenced.
     */
    @Column(name = "provider_media_id", length = 190)
    private String providerMediaId;

    @Column(name = "owner_user_id")
    private Long ownerUserId;
}
