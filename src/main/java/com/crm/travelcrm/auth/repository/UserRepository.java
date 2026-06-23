package com.crm.travelcrm.auth.repository;

import com.crm.travelcrm.auth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    Optional<User> findByEmailAndTenantId(String email, Long tenantId);
    // Soft-delete-aware variants — used by login + the JWT filter so a soft-deleted
    // user is never authenticated (a deleted row must not resolve to a principal).
    Optional<User> findByEmailAndDeletedAtIsNull(String email);
    Optional<User> findByEmailAndTenantIdAndDeletedAtIsNull(String email, Long tenantId);
    boolean existsByEmail(String email);
    boolean existsByEmailAndTenantId(String email, Long tenantId);
    List<User> findByTenantIdAndRoleInAndIsActiveTrue(Long tenantId, List<String> roles);
    List<User> findAllByTenantId(Long tenantId);
    List<User> findAllByTenantIdAndDeletedAtIsNull(Long tenantId);
    Optional<User> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);
    Optional<User> findByIdAndTenantIdAndDeletedAtIsNull(Long id, Long tenantId);
    void deleteByTenantId(Long tenantId);
    List<User> findByTenantIdAndIsActiveTrueAndDeletedAtIsNullOrderByNameAsc(Long tenantId);
}