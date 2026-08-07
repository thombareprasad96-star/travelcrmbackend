package com.crm.travelcrm.communication.repository;

import com.crm.travelcrm.communication.entity.CommTemplate;
import com.crm.travelcrm.communication.enums.CommChannel;
import com.crm.travelcrm.communication.enums.TemplateStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The tenant's message-template library — the first reusable message template in this codebase. */
public interface CommTemplateRepository
        extends JpaRepository<CommTemplate, Long>, JpaSpecificationExecutor<CommTemplate> {

    Optional<CommTemplate> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    /** What a composer offers: active templates for the channel it is composing on. */
    List<CommTemplate> findByTenantIdAndChannelAndStatusAndDeletedAtIsNullOrderByNameAsc(
            Long tenantId, CommChannel channel, TemplateStatus status);

    /** Quick replies for the WhatsApp panel — templates are the only place they live. */
    List<CommTemplate> findByTenantIdAndChannelAndCategoryAndStatusAndDeletedAtIsNullOrderByNameAsc(
            Long tenantId, CommChannel channel, String category, TemplateStatus status);

    /** Name is unique per channel within a tenant, so a composer's picker is unambiguous. */
    Optional<CommTemplate> findByTenantIdAndChannelAndNameIgnoreCaseAndDeletedAtIsNull(
            Long tenantId, CommChannel channel, String name);

    boolean existsByTenantIdAndChannelAndNameIgnoreCaseAndDeletedAtIsNull(
            Long tenantId, CommChannel channel, String name);
}
