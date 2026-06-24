package com.crm.travelcrm.permission.repository;

import com.crm.travelcrm.permission.entity.UserPermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserPermissionRepository extends JpaRepository<UserPermission, Long> {
    // Explicit tenantId scoping (the Hibernate @Filter is only active inside the
    // TenantFilterAspect's transactions; an explicit param is always safe).
    Optional<UserPermission> findByTenantIdAndUserId(Long tenantId, Long userId);
}