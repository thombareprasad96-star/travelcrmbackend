package com.crm.travelcrm.auth.repository;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.enums.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    // ── Stats (Users page cards) — all scoped to tenant, excluding soft-deleted ──
    long countByTenantIdAndDeletedAtIsNull(Long tenantId);
    long countByTenantIdAndDeletedAtIsNullAndIsActiveTrue(Long tenantId);
    long countByTenantIdAndDeletedAtIsNullAndIsActiveFalse(Long tenantId);
    long countByTenantIdAndDeletedAtIsNullAndRole(Long tenantId, Role role);

    // Free-text search over name / email / phone within the caller's tenant.
    @Query("""
            SELECT u FROM User u
            WHERE u.tenantId = :tenantId AND u.deletedAt IS NULL
              AND (LOWER(u.name) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(u.email) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(COALESCE(u.phoneNumber, '')) LIKE LOWER(CONCAT('%', :q, '%')))
            ORDER BY u.name ASC
            """)
    List<User> searchInTenant(@Param("tenantId") Long tenantId, @Param("q") String q);

    /** IDs of active team members reporting to this manager (User.managerId) — for scope filtering. */
    @Query("SELECT u.id FROM User u WHERE u.tenantId = :tenantId AND u.managerId = :managerId AND u.deletedAt IS NULL")
    List<Long> findIdsByTenantIdAndManagerId(@Param("tenantId") Long tenantId, @Param("managerId") Long managerId);
}