package com.crm.travelcrm.quotation.analytics;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

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
}