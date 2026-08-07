package com.crm.travelcrm.quotation.analytics;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface QuotationWeblinkViewRepository extends JpaRepository<QuotationWeblinkView, Long> {

    // Upsert lookup for the public write path (tenant set explicitly from the quotation).
    Optional<QuotationWeblinkView> findByTenantIdAndQuotationPublicIdAndIpAddress(
            Long tenantId, UUID quotationPublicId, String ipAddress);

    // Authed analytics read — explicitly tenant-scoped (this entity is not Hibernate-filtered).
    List<QuotationWeblinkView> findAllByQuotationPublicIdAndTenantIdOrderByViewCountDescLastViewedAtDesc(
            UUID quotationPublicId, Long tenantId);

    /**
     * View tally for many quotations at once — one query for a whole page of the lead list, so the
     * badge on each row costs nothing per row. Rows are stored per viewer IP, so the count has to be
     * summed, not counted. Explicitly tenant-scoped: this entity is not Hibernate-filtered because
     * the public write path runs with no TenantContext.
     *
     * <p>{@code viewerType} is a parameter rather than a hard-coded EXTERNAL so the caller states
     * which audience it is asking about — the lead-list badge means "the client opened it", which is
     * a different question from the analytics modal's total.
     */
    @Query("""
            SELECT v.quotationPublicId AS quotationPublicId, SUM(v.viewCount) AS views
              FROM QuotationWeblinkView v
             WHERE v.quotationPublicId IN :ids
               AND v.tenantId = :tenantId
               AND v.viewerType = :viewerType
             GROUP BY v.quotationPublicId
            """)
    List<QuotationViewTally> tallyViews(@Param("ids") Collection<UUID> ids,
                                        @Param("tenantId") Long tenantId,
                                        @Param("viewerType") ViewerType viewerType);

    /** Closed projection for {@link #tallyViews}. */
    interface QuotationViewTally {
        UUID getQuotationPublicId();
        Long getViews();
    }
}