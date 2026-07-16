package com.crm.travelcrm.accounting.tax.repository;

import com.crm.travelcrm.accounting.tax.entity.HsnSacRate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HsnSacRateRepository extends JpaRepository<HsnSacRate, Long> {

    List<HsnSacRate> findByTenantIdAndDeletedAtIsNullOrderByCategoryAscCodeAsc(Long tenantId);

    Optional<HsnSacRate> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    Optional<HsnSacRate> findByTenantIdAndCodeAndDeletedAtIsNull(Long tenantId, String code);

    Optional<HsnSacRate> findFirstByTenantIdAndIsDefaultTrueAndDeletedAtIsNull(Long tenantId);

    long countByTenantIdAndDeletedAtIsNull(Long tenantId);
}