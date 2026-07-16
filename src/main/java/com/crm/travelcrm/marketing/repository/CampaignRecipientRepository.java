package com.crm.travelcrm.marketing.repository;

import com.crm.travelcrm.marketing.entity.CampaignRecipient;
import com.crm.travelcrm.marketing.enums.RecipientStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CampaignRecipientRepository extends JpaRepository<CampaignRecipient, Long> {

    Page<CampaignRecipient> findByCampaignIdAndTenantId(Long campaignId, Long tenantId, Pageable pageable);

    Page<CampaignRecipient> findByCampaignIdAndTenantIdAndStatus(Long campaignId, Long tenantId, RecipientStatus status, Pageable pageable);

    // Dispatch batch: the next slice of pending recipients (bounded via Pageable).
    List<CampaignRecipient> findByCampaignIdAndTenantIdAndStatusOrderByIdAsc(Long campaignId, Long tenantId, RecipientStatus status, Pageable pageable);

    long countByCampaignIdAndTenantId(Long campaignId, Long tenantId);

    long countByCampaignIdAndTenantIdAndStatus(Long campaignId, Long tenantId, RecipientStatus status);

    boolean existsByCampaignIdAndTenantIdAndStatus(Long campaignId, Long tenantId, RecipientStatus status);
}