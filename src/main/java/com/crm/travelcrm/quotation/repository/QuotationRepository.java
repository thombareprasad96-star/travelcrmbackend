package com.crm.travelcrm.quotation.repository;

import com.crm.travelcrm.quotation.entity.Quotation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface QuotationRepository
        extends JpaRepository<Quotation, Long>, JpaSpecificationExecutor<Quotation> {

    // ── Single fetch (tenant-scoped, never bare findById) ─────────────────────
    Optional<Quotation> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    // ── List ──────────────────────────────────────────────────────────────────
    Page<Quotation> findAllByTenantIdAndDeletedAtIsNull(Long tenantId, Pageable pageable);

    // ── By lead ────────────────────────────────────────────────────────────────
    List<Quotation> findAllByLeadPublicIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            UUID leadPublicId, Long tenantId);

    long countByLeadPublicIdAndTenantIdAndDeletedAtIsNull(UUID leadPublicId, Long tenantId);

    // ── Quote-number sequence ───────────────────────────────────────────────────
    // Counts root quotations (a family's first version) for a tenant. The next quote
    // number is this + 1. Counts deleted rows too, so numbers are never reused.
    long countByTenantIdAndParentQuotationIdIsNull(Long tenantId);

    // ── Cascade soft-delete (driven by LeadSoftDeletedEvent) ──────────────────
    // Single bulk UPDATE; tenant id is in the WHERE clause so it is safe regardless
    // of the Hibernate tenant filter. Only touches live rows.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Quotation q
               SET q.deletedAt = :deletedAt, q.deletedBy = :deletedBy
             WHERE q.leadId = :leadId
               AND q.tenantId = :tenantId
               AND q.deletedAt IS NULL
            """)
    int softDeleteByLeadId(@Param("leadId") Long leadId,
                           @Param("tenantId") Long tenantId,
                           @Param("deletedAt") LocalDateTime deletedAt,
                           @Param("deletedBy") String deletedBy);

    // ── Versioning ────────────────────────────────────────────────────────────
    // Highest version number across a family (the root plus all of its versions),
    // tenant-scoped, ignoring soft-deleted rows. Returns 0 when none match.
    @Query("""
            SELECT COALESCE(MAX(q.versionNumber), 0)
              FROM Quotation q
             WHERE (q.id = :rootId OR q.parentQuotationId = :rootId)
               AND q.tenantId = :tenantId
               AND q.deletedAt IS NULL
            """)
    int findMaxVersionInFamily(@Param("rootId") Long rootId, @Param("tenantId") Long tenantId);
}