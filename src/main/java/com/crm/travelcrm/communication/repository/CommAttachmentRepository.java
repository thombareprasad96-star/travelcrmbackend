package com.crm.travelcrm.communication.repository;

import com.crm.travelcrm.communication.entity.CommAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Message attachments.
 *
 * <p><b>Never load the entity for a list.</b> {@code content} is a {@code byte[]} and JPA fetches it
 * eagerly, so {@link #findByPublicIdAndTenantIdAndDeletedAtIsNull} is correct only on the
 * single-file download path. Everything else uses {@link CommAttachmentView}, which omits the
 * blob — the same split {@code TravelerDocumentRepository} uses.
 *
 * <p>{@link #sumBytesByTenant(Long)} is what the storage quota gate reads. If it is added there, it
 * must be added to {@code UsageServiceImpl} and {@code UsageAlertService} in the same change, or the
 * gate blocks at a number the SuperAdmin dashboard cannot see.
 */
public interface CommAttachmentRepository extends JpaRepository<CommAttachment, Long> {

    /** Loads the bytes. Download path only. */
    Optional<CommAttachment> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    /** Blob-free metadata for one message. */
    List<CommAttachmentView> findByTenantIdAndMessageIdAndDeletedAtIsNull(Long tenantId, Long messageId);

    /** Blob-free metadata for a page of messages — one query for a whole timeline page. */
    List<CommAttachmentView> findByTenantIdAndMessageIdInAndDeletedAtIsNull(
            Long tenantId, Collection<Long> messageIds);

    long countByTenantIdAndMessageIdAndDeletedAtIsNull(Long tenantId, Long messageId);

    /** Bytes this tenant holds in message attachments — a term in the storage quota. */
    @Query("""
            SELECT COALESCE(SUM(a.sizeBytes), 0) FROM CommAttachment a
            WHERE a.tenantId = :tenantId AND a.deletedAt IS NULL
            """)
    long sumBytesByTenant(@Param("tenantId") Long tenantId);

    /** Per-tenant totals for the platform usage dashboard. Rows are {@code [tenantId, bytes]}. */
    @Query("""
            SELECT a.tenantId, COALESCE(SUM(a.sizeBytes), 0) FROM CommAttachment a
            WHERE a.deletedAt IS NULL
            GROUP BY a.tenantId
            """)
    List<Object[]> sumBytesGroupedByTenant();
}
