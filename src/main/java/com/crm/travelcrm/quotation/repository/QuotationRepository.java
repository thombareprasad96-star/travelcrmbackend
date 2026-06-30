package com.crm.travelcrm.quotation.repository;

import com.crm.travelcrm.quotation.entity.Quotation;
import com.crm.travelcrm.quotation.enums.DiscountType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface QuotationRepository
        extends JpaRepository<Quotation, Long>, JpaSpecificationExecutor<Quotation> {

    // ── Single fetch (tenant-scoped, never bare findById) ─────────────────────
    Optional<Quotation> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    // Tenant-agnostic lookup for the PUBLIC share link only (publicId is a globally-unique,
    // unguessable UUID — capability URL). Never use this on authenticated, tenant-scoped paths.
    Optional<Quotation> findByPublicIdAndDeletedAtIsNull(UUID publicId);

    // ── List ──────────────────────────────────────────────────────────────────
    Page<Quotation> findAllByTenantIdAndDeletedAtIsNull(Long tenantId, Pageable pageable);

    // ── By lead ────────────────────────────────────────────────────────────────
    List<Quotation> findAllByLeadPublicIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            UUID leadPublicId, Long tenantId);

    long countByLeadPublicIdAndTenantIdAndDeletedAtIsNull(UUID leadPublicId, Long tenantId);

    // Live quotations for a lead by internal leadId — loaded as entities so the cancel-booking
    // "permanent delete lead" path can soft-delete them explicitly (never silently orphaned).
    List<Quotation> findAllByLeadIdAndTenantIdAndDeletedAtIsNull(Long leadId, Long tenantId);

    // ── Latest quotation for a lead ─────────────────────────────────────────────
    // "Latest" = newest row by createdAt (versioning is new-row, so the newest row is
    // the newest version), id DESC as a deterministic tiebreaker. Tenant-scoped.
    Optional<Quotation> findFirstByLeadPublicIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(
            UUID leadPublicId, Long tenantId);

    // Lean batch projection for embedding in the lead list/board — selects ONLY the
    // ids needed to render a View/Download action, never loading the (wide) Quotation
    // entity. One query for many leads, reduced to latest-per-lead in the service
    // (JPQL has no Postgres DISTINCT ON).
    @Query("""
            SELECT q.leadPublicId AS leadPublicId, q.publicId AS quotationPublicId,
                   q.flightAmount AS flightAmount, q.hotelAmount AS hotelAmount,
                   q.sightseeingAmount AS sightseeingAmount, q.cruiseAmount AS cruiseAmount,
                   q.vehicleAmount AS vehicleAmount, q.addonAmount AS addonAmount,
                   q.discount AS discount, q.discountType AS discountType,
                   q.tax AS tax, q.markup AS markup
              FROM Quotation q
             WHERE q.leadPublicId IN :ids
               AND q.tenantId = :tenantId
               AND q.deletedAt IS NULL
             ORDER BY q.createdAt DESC, q.id DESC
            """)
    List<LatestQuotationRef> findLatestRefsForLeads(
            @Param("ids") Collection<UUID> ids, @Param("tenantId") Long tenantId);

    /**
     * Closed projection: a lead's publicId, its latest quotation's publicId, and the raw pricing
     * columns needed to compute that quotation's grand total (via {@code QuotationMapper.computeTotals})
     * for the lead list — without loading the wide Quotation entity or its collections.
     */
    interface LatestQuotationRef {
        UUID getLeadPublicId();
        UUID getQuotationPublicId();
        BigDecimal getFlightAmount();
        BigDecimal getHotelAmount();
        BigDecimal getSightseeingAmount();
        BigDecimal getCruiseAmount();
        BigDecimal getVehicleAmount();
        BigDecimal getAddonAmount();
        BigDecimal getDiscount();
        DiscountType getDiscountType();
        BigDecimal getTax();
        BigDecimal getMarkup();
    }

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

    // ── Cascade restore (driven by LeadRestoredEvent) ─────────────────────────
    // Symmetric with softDeleteByLeadId: when a trashed lead is restored, bring its quotations
    // back too. Only touches rows still in Trash (deletedAt IS NOT NULL); tenant id is in the
    // WHERE clause so it is safe regardless of the Hibernate tenant filter.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE Quotation q
               SET q.deletedAt = NULL, q.deletedBy = NULL
             WHERE q.leadId = :leadId
               AND q.tenantId = :tenantId
               AND q.deletedAt IS NOT NULL
            """)
    int restoreByLeadId(@Param("leadId") Long leadId, @Param("tenantId") Long tenantId);

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