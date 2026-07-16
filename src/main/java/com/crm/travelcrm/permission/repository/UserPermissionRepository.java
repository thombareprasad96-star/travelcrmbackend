package com.crm.travelcrm.permission.repository;

import com.crm.travelcrm.permission.entity.UserPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserPermissionRepository extends JpaRepository<UserPermission, Long> {
    // Explicit tenantId scoping (the Hibernate @Filter is only active inside the
    // TenantFilterAspect's transactions; an explicit param is always safe).
    Optional<UserPermission> findByTenantIdAndUserId(Long tenantId, Long userId);

    /**
     * Batch-load the saved permission rows for many users in ONE query — avoids the N+1 that a
     * per-user {@link #findByTenantIdAndUserId} loop would produce when resolving an eligible-user
     * pool. Users without a saved row are simply absent from the result.
     */
    List<UserPermission> findByTenantIdAndUserIdIn(Long tenantId, Collection<Long> userIds);
}