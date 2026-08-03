package com.crm.travelcrm.fleet.repository;

import com.crm.travelcrm.fleet.entity.FleetAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FleetAttachmentRepository extends JpaRepository<FleetAttachment, Long> {

    /**
     * List projection — every column EXCEPT the blob. Loading {@code content} to render a file list
     * is how a 10-row screen reads 80 MB; the portal's {@code TravelerDocumentView} is the precedent.
     */
    interface FleetAttachmentView {
        UUID getPublicId();
        String getFileName();
        String getContentType();
        long getSizeBytes();
        String getSha256();
        LocalDateTime getCreatedAt();
        String getCreatedBy();
    }

    /** Full row (with bytes) — for the download endpoint only. */
    Optional<FleetAttachment> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    List<FleetAttachmentView> findAllByTenantIdAndExpenseIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            Long tenantId, Long expenseId);

    List<FleetAttachmentView> findAllByTenantIdAndDocumentIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            Long tenantId, Long documentId);

    List<FleetAttachmentView> findAllByTenantIdAndSettlementIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            Long tenantId, Long settlementId);

    /**
     * Stored fleet-attachment bytes for one tenant. Same shape and same soft-delete semantics as
     * {@code TravelerDocumentRepository.sumBytesByTenant} — the three byte sources MUST agree,
     * because this figure feeds the quota gate, the usage dashboard and the alert scheduler alike.
     */
    @Query("SELECT COALESCE(SUM(a.sizeBytes), 0) FROM FleetAttachment a "
            + "WHERE a.deletedAt IS NULL AND a.tenantId = :tenantId")
    long sumBytesByTenant(@Param("tenantId") Long tenantId);
}
