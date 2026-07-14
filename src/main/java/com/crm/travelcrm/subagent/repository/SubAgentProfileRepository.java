package com.crm.travelcrm.subagent.repository;

import com.crm.travelcrm.subagent.entity.SubAgentProfile;
import com.crm.travelcrm.subagent.enums.SubAgentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SubAgentProfileRepository extends JpaRepository<SubAgentProfile, Long> {

    Optional<SubAgentProfile> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    Optional<SubAgentProfile> findByUserIdAndTenantIdAndDeletedAtIsNull(Long userId, Long tenantId);

    /**
     * Tenant-agnostic lookup by the owning user id — used by the Phase 4 markup/branding resolver on
     * the PUBLIC quotation share-link path, where there is no TenantContext. userId is globally unique.
     */
    Optional<SubAgentProfile> findByUserIdAndDeletedAtIsNull(Long userId);

    List<SubAgentProfile> findAllByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long tenantId);

    /** Active-seat count for the Phase 3B subscription seat-fee. */
    long countByTenantIdAndStatusAndDeletedAtIsNull(Long tenantId, SubAgentStatus status);
}
