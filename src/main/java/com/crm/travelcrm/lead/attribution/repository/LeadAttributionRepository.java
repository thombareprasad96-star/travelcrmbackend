package com.crm.travelcrm.lead.attribution.repository;

import com.crm.travelcrm.lead.attribution.entity.LeadAttribution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LeadAttributionRepository extends JpaRepository<LeadAttribution, Long> {

    /**
     * The detail path's explicit fetch.
     *
     * <p>{@code Lead} deliberately does NOT map the inverse side: a nullable inverse {@code @OneToOne}
     * cannot be proxied without bytecode enhancement, so Hibernate would fire an extra SELECT per lead
     * in the 100-row list query — the exact N+1 this separate table exists to avoid.
     */
    Optional<LeadAttribution> findByLeadIdAndTenantIdAndDeletedAtIsNull(Long leadId, Long tenantId);

    List<LeadAttribution> findByTenantIdAndCampaignNameAndDeletedAtIsNull(Long tenantId, String campaignName);

    boolean existsByLeadIdAndTenantIdAndDeletedAtIsNull(Long leadId, Long tenantId);
}
