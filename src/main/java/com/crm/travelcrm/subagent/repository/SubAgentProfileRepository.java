package com.crm.travelcrm.subagent.repository;

import com.crm.travelcrm.subagent.entity.SubAgentProfile;
import com.crm.travelcrm.subagent.enums.SubAgentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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

    /** Provisioned (non-deleted, any status) count — the number that counts against the tenant cap. */
    long countByTenantIdAndDeletedAtIsNull(Long tenantId);

    /**
     * Provisioned sub-agent counts grouped by tenant across ALL tenants — one query for the
     * SuperAdmin usage dashboard (avoids an N+1). Runs from the SuperAdmin thread where
     * {@code TenantContext} is null, so the Hibernate tenant filter is off. Each row is
     * {@code [tenantId (Long), count (Long)]}.
     */
    @Query("""
            SELECT p.tenantId, COUNT(p) FROM SubAgentProfile p
            WHERE p.deletedAt IS NULL AND p.tenantId IS NOT NULL
            GROUP BY p.tenantId
            """)
    List<Object[]> countProvisionedGroupedByTenant();
}
