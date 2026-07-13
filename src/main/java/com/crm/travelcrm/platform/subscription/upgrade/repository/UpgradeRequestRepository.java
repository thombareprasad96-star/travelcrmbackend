package com.crm.travelcrm.platform.subscription.upgrade.repository;

import com.crm.travelcrm.platform.subscription.upgrade.entity.UpgradeRequest;
import com.crm.travelcrm.platform.subscription.upgrade.enums.UpgradeRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Platform-level upgrade requests. No tenant {@code @Filter} (BaseEntity), so tenant-facing reads are
 * scoped explicitly by {@code tenantId} and every lookup filters {@code deletedAt IS NULL}.
 */
@Repository
public interface UpgradeRequestRepository extends JpaRepository<UpgradeRequest, Long> {

    Optional<UpgradeRequest> findByPublicIdAndDeletedAtIsNull(UUID publicId);

    /** A tenant's own requests, newest first. */
    List<UpgradeRequest> findByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long tenantId);

    /** Used to enforce "one live PENDING request per tenant". */
    List<UpgradeRequest> findByTenantIdAndStatusAndDeletedAtIsNull(Long tenantId, UpgradeRequestStatus status);

    /** SuperAdmin console: all requests, newest first. */
    List<UpgradeRequest> findByDeletedAtIsNullOrderByCreatedAtDesc();

    /** SuperAdmin console: filtered by status, newest first. */
    List<UpgradeRequest> findByStatusAndDeletedAtIsNullOrderByCreatedAtDesc(UpgradeRequestStatus status);

    /** Pending badge count for the console. */
    long countByStatusAndDeletedAtIsNull(UpgradeRequestStatus status);
}