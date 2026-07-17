package com.crm.travelcrm.auth.repository;

import com.crm.travelcrm.auth.entity.User;
import com.crm.travelcrm.auth.enums.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    // ── Email finders ─────────────────────────────────────────────────────────
    // Email is unique PLATFORM-WIDE among live rows (uq_users_email_active, db/indexes.sql), so an
    // address needs no tenant to resolve — and must NOT be checked per-tenant: a tenant-scoped
    // uniqueness check passes for a tenant that has not seen the address and then dies on the
    // constraint. The per-tenant email finders were deliberately removed for that reason; if you
    // are reaching for one, you want the global check below.
    //
    // Soft-delete-aware by design — a deleted row must never resolve to a principal, and the
    // constraint only covers live rows, so a non-soft-delete-aware finder could match a deleted
    // squatter. Every email lookup here filters deletedAt.
    Optional<User> findByEmailAndDeletedAtIsNull(String email);
    Optional<User> findByEmailAndTenantIdAndDeletedAtIsNull(String email, Long tenantId);
    /** Returns a list purely so an ambiguous match fails closed instead of throwing a 500 — see
     *  UserDetailsServiceImpl. Under an intact constraint this can never exceed one row. */
    List<User> findAllByEmailAndDeletedAtIsNull(String email);
    Optional<User> findByPublicIdAndDeletedAtIsNull(UUID publicId);
    /** The uniqueness check for every user-creating path. Global, matching the DB constraint. */
    boolean existsByEmail(String email);
    List<User> findByTenantIdAndRoleInAndIsActiveTrue(Long tenantId, List<String> roles);
    List<User> findAllByTenantId(Long tenantId);
    List<User> findAllByTenantIdAndDeletedAtIsNull(Long tenantId);
    Optional<User> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);
    Optional<User> findByIdAndTenantIdAndDeletedAtIsNull(Long id, Long tenantId);

    /**
     * Batch id → user lookup, tenant-scoped. Exists so a paged response can resolve every row's
     * assignee name in ONE query instead of one per row; ids belonging to another tenant simply
     * do not come back, so a leaked id yields a blank name rather than a foreign user's.
     * Soft-deleted users ARE included — a booking assigned to a since-deleted user must still be
     * able to display who that was.
     */
    List<User> findByIdInAndTenantId(Collection<Long> ids, Long tenantId);

    void deleteByTenantId(Long tenantId);
    List<User> findByTenantIdAndIsActiveTrueAndDeletedAtIsNullOrderByNameAsc(Long tenantId);

    // ── Stats (Users page cards) — all scoped to tenant, excluding soft-deleted ──
    long countByTenantIdAndDeletedAtIsNull(Long tenantId);
    long countByTenantIdAndDeletedAtIsNullAndIsActiveTrue(Long tenantId);
    long countByTenantIdAndDeletedAtIsNullAndIsActiveFalse(Long tenantId);
    long countByTenantIdAndDeletedAtIsNullAndRole(Long tenantId, Role role);

    /**
     * Staff-seat count = users excluding a role. Used by the {@code maxUsers} gate to exclude
     * B2B sub-agents, which have their own separate cap ({@code Tenant.maxSubAgents}) and seat fee,
     * so a sub-agent never consumes a staff seat.
     */
    long countByTenantIdAndDeletedAtIsNullAndRoleNot(Long tenantId, Role role);

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