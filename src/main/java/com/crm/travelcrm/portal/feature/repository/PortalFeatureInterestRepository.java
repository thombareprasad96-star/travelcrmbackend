package com.crm.travelcrm.portal.feature.repository;

import com.crm.travelcrm.portal.feature.PortalFeatureKey;
import com.crm.travelcrm.portal.feature.dto.FeatureInterestSummaryDto;
import com.crm.travelcrm.portal.feature.entity.PortalFeatureInterest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PortalFeatureInterestRepository extends JpaRepository<PortalFeatureInterest, Long> {

    boolean existsByTenantIdAndCustomerIdAndFeatureKey(
            Long tenantId, Long customerId, PortalFeatureKey featureKey);

    List<PortalFeatureInterest> findByTenantIdAndCustomerId(Long tenantId, Long customerId);

    /** Staff-side interest summary — count of registrations per feature within the tenant. */
    @Query("""
            SELECT new com.crm.travelcrm.portal.feature.dto.FeatureInterestSummaryDto(p.featureKey, COUNT(p))
            FROM PortalFeatureInterest p
            WHERE p.tenantId = :tenantId
            GROUP BY p.featureKey
            ORDER BY COUNT(p) DESC
            """)
    List<FeatureInterestSummaryDto> summarize(@Param("tenantId") Long tenantId);
}