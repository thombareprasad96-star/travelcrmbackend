package com.crm.travelcrm.auth.repository;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.enums.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
    // Platform (cross-tenant) — SuperAdmin. Email may match multiple tenants, so the fail-safe login
    // lookup returns a list; publicId is globally unique so its finder stays an Optional.
    List<User> findAllByEmailAndDeletedAtIsNull(String email);
    Optional<User> findByPublicIdAndDeletedAtIsNull(UUID publicId);
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

    /** Total tenant users across the platform (excludes platform users with a null tenantId). */
    long countByDeletedAtIsNullAndTenantIdIsNotNull();

    /**
     * Active (non-deleted) user counts grouped by tenant — one query for a whole page of tenants
     * (platform console tenant list, avoids an N+1). Each row is [tenantId (Long), count (Long)].
     */
    @Query("""
            SELECT u.tenantId, COUNT(u) FROM User u
            WHERE u.deletedAt IS NULL AND u.tenantId IN :tenantIds
            GROUP BY u.tenantId
            """)
    List<Object[]> countActiveGroupedByTenant(@Param("tenantIds") List<Long> tenantIds);

    /**
     * Active (isActive + non-deleted) tenant-user counts grouped by tenant across ALL tenants —
     * one query for the SuperAdmin usage dashboard. {@code User} extends {@code BaseEntity} (no
     * tenant filter), so this returns every tenant regardless of {@code TenantContext}. Each row is
     * {@code [tenantId (Long), count (Long)]}. Unlike {@link #countActiveGroupedByTenant} this also
     * requires {@code isActive = true} (seat usage, not just provisioned).
     */
    @Query("""
            SELECT u.tenantId, COUNT(u) FROM User u
            WHERE u.deletedAt IS NULL AND u.isActive = true AND u.tenantId IS NOT NULL
            GROUP BY u.tenantId
            """)
    List<Object[]> countActiveUsersByTenant();

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

    /** Active (non-deleted) user ids for a tenant — announcement fan-out (ALL_USERS). */
    @Query("SELECT u.id FROM User u WHERE u.tenantId = :tenantId AND u.isActive = true AND u.deletedAt IS NULL")
    List<Long> findActiveUserIds(@Param("tenantId") Long tenantId);

    /** Active (non-deleted) user ids of a given role for a tenant — announcement fan-out (ADMINS). */
    @Query("SELECT u.id FROM User u WHERE u.tenantId = :tenantId AND u.role = :role AND u.isActive = true AND u.deletedAt IS NULL")
    List<Long> findActiveUserIdsByRole(@Param("tenantId") Long tenantId, @Param("role") Role role);

    /**
     * Cross-tenant user search for the platform console (SuperAdmin). Tenant users only
     * ({@code tenantId} not null); optional tenant filter; free-text over name/email. Each bind
     * param is null/blank-guarded so an empty search / null tenant returns everything.
     */
    @Query("""
            SELECT u FROM User u
            WHERE u.deletedAt IS NULL AND u.tenantId IS NOT NULL
              AND (:tenantId IS NULL OR u.tenantId = :tenantId)
              AND (:q = '' OR LOWER(u.name) LIKE LOWER(CONCAT('%', :q, '%'))
                           OR LOWER(u.email) LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    Page<User> platformSearch(@Param("q") String q, @Param("tenantId") Long tenantId, Pageable pageable);
}