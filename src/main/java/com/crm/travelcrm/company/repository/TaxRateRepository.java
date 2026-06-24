package com.crm.travelcrm.company.repository;

import com.crm.travelcrm.company.entity.TaxRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TaxRateRepository extends JpaRepository<TaxRate, Long> {
    List<TaxRate> findByTenantIdOrderByEffectiveFromDesc(Long tenantId);
    List<TaxRate> findByTenantIdAndIsActiveTrueOrderByTypeAsc(Long tenantId);
    List<TaxRate> findByTenantIdAndTypeAndIsActiveTrue(Long tenantId, String type);
    Optional<TaxRate> findByPublicIdAndTenantId(UUID publicId, Long tenantId);
}