package com.crm.travelcrm.subagent.license.repository;

import com.crm.travelcrm.subagent.license.entity.SubAgentLicenseRequest;
import com.crm.travelcrm.subagent.license.enums.SubAgentLicenseRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Platform-level sub-agent seat-license requests. No tenant {@code @Filter} (BaseEntity), so
 * tenant-facing reads are scoped explicitly by {@code tenantId} and every lookup filters
 * {@code deletedAt IS NULL}.
 */
@Repository
public interface SubAgentLicenseRequestRepository extends JpaRepository<SubAgentLicenseRequest, Long> {

    Optional<SubAgentLicenseRequest> findByPublicIdAndDeletedAtIsNull(UUID publicId);

    /** A tenant's own requests, newest first. */
    List<SubAgentLicenseRequest> findByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long tenantId);

    /** Guard: is there already a live request for this exact pending sub-agent? */
    List<SubAgentLicenseRequest> findBySubAgentProfileIdAndStatusAndDeletedAtIsNull(
            Long subAgentProfileId, SubAgentLicenseRequestStatus status);

    /** SuperAdmin console: all requests, newest first. */
    List<SubAgentLicenseRequest> findByDeletedAtIsNullOrderByCreatedAtDesc();

    /** SuperAdmin console: filtered by status, newest first. */
    List<SubAgentLicenseRequest> findByStatusAndDeletedAtIsNullOrderByCreatedAtDesc(SubAgentLicenseRequestStatus status);

    /** Pending badge count for the console. */
    long countByStatusAndDeletedAtIsNull(SubAgentLicenseRequestStatus status);
}