package com.crm.travelcrm.marketing.repository;

import com.crm.travelcrm.marketing.entity.Campaign;
import com.crm.travelcrm.marketing.enums.CampaignStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CampaignRepository extends JpaRepository<Campaign, Long>, JpaSpecificationExecutor<Campaign> {

    Optional<Campaign> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    // Scheduler: SCHEDULED campaigns whose time has arrived.
    List<Campaign> findByTenantIdAndStatusAndScheduledAtLessThanEqualAndDeletedAtIsNull(
            Long tenantId, CampaignStatus status, LocalDateTime now);

    // Scheduler: resume in-flight SENDING campaigns that still have pending recipients.
    List<Campaign> findByTenantIdAndStatusAndDeletedAtIsNull(Long tenantId, CampaignStatus status);

    long countByTenantIdAndDeletedAtIsNull(Long tenantId);

    long countByTenantIdAndStatusAndDeletedAtIsNull(Long tenantId, CampaignStatus status);

    @Query("""
            SELECT COALESCE(SUM(c.sentCount), 0) FROM Campaign c
            WHERE c.tenantId = :tenantId AND c.deletedAt IS NULL AND c.sentAt >= :since
            """)
    long sumSentCountSince(@Param("tenantId") Long tenantId, @Param("since") LocalDateTime since);
}