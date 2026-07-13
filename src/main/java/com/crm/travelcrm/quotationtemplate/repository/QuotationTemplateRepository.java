package com.crm.travelcrm.quotationtemplate.repository;

import com.crm.travelcrm.quotationtemplate.entity.QuotationTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every finder is tenant-scoped and live-rows-only. There is deliberately no {@code findById(Long)}
 * usage anywhere in this module: the Hibernate tenant filter does not apply to
 * {@code EntityManager.find}, so a bare id lookup would read across tenants.
 */
@Repository
public interface QuotationTemplateRepository
        extends JpaRepository<QuotationTemplate, Long>, JpaSpecificationExecutor<QuotationTemplate> {

    Optional<QuotationTemplate> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    /** The matching candidate set: live, active templates for this tenant. */
    List<QuotationTemplate> findAllByTenantIdAndActiveTrueAndDeletedAtIsNull(Long tenantId);

    // ── Duplicate-name guard ──────────────────────────────────────────────────
    // Scoped to live rows on purpose: a soft-deleted template must not permanently reserve its name.
    boolean existsByTenantIdAndNameIgnoreCaseAndDeletedAtIsNull(Long tenantId, String name);

    boolean existsByTenantIdAndNameIgnoreCaseAndDeletedAtIsNullAndIdNot(Long tenantId, String name, long id);
}