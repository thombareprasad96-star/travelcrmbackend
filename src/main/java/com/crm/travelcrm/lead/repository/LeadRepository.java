package com.crm.travelcrm.lead.repository;

import com.crm.travelcrm.lead.dto.UserLeadStageCountDto;
import com.crm.travelcrm.lead.dto.UserWorkloadDto;
import com.crm.travelcrm.lead.entity.Lead;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LeadRepository extends JpaRepository<Lead, Long> {

    // ── List ─────────────────────────────────────────────────────────────────
    @EntityGraph(attributePaths = "assignedUser")
    Page<Lead> findAllByTenantIdAndDeletedAtIsNull(
            Long tenantId, Pageable pageable);

    /**
     * Full unpaged fetch for the Kanban board — every live lead of the tenant,
     * newest first, with the assignee eagerly joined to avoid N+1.
     */
    @EntityGraph(attributePaths = "assignedUser")
    List<Lead> findAllByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long tenantId);

    // ── Scope-filtered variants (own / team): owner_id IN (:assignedUserIds) ──
    @EntityGraph(attributePaths = "assignedUser")
    Page<Lead> findAllByTenantIdAndDeletedAtIsNullAndAssignedUser_IdIn(
            Long tenantId, Collection<Long> assignedUserIds, Pageable pageable);

    @EntityGraph(attributePaths = "assignedUser")
    List<Lead> findAllByTenantIdAndDeletedAtIsNullAndAssignedUser_IdInOrderByCreatedAtDesc(
            Long tenantId, Collection<Long> assignedUserIds);

    // ── Single fetch ─────────────────────────────────────────────────────────
    @EntityGraph(attributePaths = "assignedUser")
    Optional<Lead> findByPublicIdAndTenantIdAndDeletedAtIsNull(
            UUID publicId, Long tenantId);

    // ── Search ───────────────────────────────────────────────────────────────
    @EntityGraph(attributePaths = "assignedUser")
    Optional<Lead> findByEmailAndTenantIdAndDeletedAtIsNull(
            String email, Long tenantId);

    @EntityGraph(attributePaths = "assignedUser")
    Optional<Lead> findByPhoneAndTenantIdAndDeletedAtIsNull(
            String phone, Long tenantId);

    // ── Existence check (cross-aggregate FK validation, e.g. Booking.leadId) ──
    boolean existsByIdAndTenantIdAndDeletedAtIsNull(Long id, Long tenantId);

    // Tenant-scoped fetch by internal id — used by the cancel-booking flow to revert
    // (REOPENED) or hard-delete the associated lead. Tenant-safe (never bare findById).
    @EntityGraph(attributePaths = "assignedUser")
    Optional<Lead> findByIdAndTenantIdAndDeletedAtIsNull(Long id, Long tenantId);

    // ── Duplicate checks ─────────────────────────────────────────────────────
    boolean existsByEmailAndTenantIdAndDeletedAtIsNull(
            String email, Long tenantId);

    boolean existsByPhoneAndTenantIdAndDeletedAtIsNull(
            String phone, Long tenantId);

    // ── Duplicate check excluding self (for update) ──────────────────────────
    boolean existsByEmailAndTenantIdAndDeletedAtIsNullAndPublicIdNot(
            String email, Long tenantId, UUID publicId);

    boolean existsByPhoneAndTenantIdAndDeletedAtIsNullAndPublicIdNot(
            String phone, Long tenantId, UUID publicId);

    // ── Trashed-only duplicate lookup (create-time "restore available" detection) ──
    // An ACTIVE duplicate is a hard "already exists" error; a match that lives ONLY in Trash
    // returns the trashed record so the API can offer Restore instead. Intercepts before insert,
    // so the DB unique constraint (uk_lead_tenant_email / uk_lead_tenant_phone) never throws a
    // raw error. Newest-first in case the same email/phone was trashed more than once.
    Optional<Lead> findFirstByEmailAndTenantIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(
            String email, Long tenantId);

    Optional<Lead> findFirstByPhoneAndTenantIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(
            String phone, Long tenantId);

    // ── Statistics ────────────────────────────────────────────────────────────
    // All aggregation runs in the database. Never load leads into memory to
    // count them, and never traverse a User→leads collection.

    /** Total live leads assigned to one user (tenant-scoped). */
    long countByAssignedUserPublicIdAndTenantIdAndDeletedAtIsNull(
            UUID userPublicId, Long tenantId);

    /**
     * Workload dashboard: every active user of the tenant with their lead
     * count. LEFT JOIN from User so members with zero leads still appear.
     */
    @Query("""
            SELECT new com.crm.travelcrm.lead.dto.UserWorkloadDto(
                u.publicId, u.name, u.email, COUNT(l))
            FROM User u
            LEFT JOIN Lead l
                   ON l.assignedUser = u
                  AND l.deletedAt IS NULL
            WHERE u.tenantId = :tenantId
              AND u.isActive = true
              AND u.deletedAt IS NULL
            GROUP BY u.publicId, u.name, u.email
            ORDER BY COUNT(l) DESC, u.name ASC
            """)
    List<UserWorkloadDto> findUserWorkload(@Param("tenantId") Long tenantId);

    /**
     * Scope-filtered workload: same as {@link #findUserWorkload} but limited to the given
     * owner ids (the caller's own/team-visible users). Used when the requester's LEAD_READ
     * scope is not ALL, so an own/team-scoped user can't enumerate the whole tenant.
     */
    @Query("""
            SELECT new com.crm.travelcrm.lead.dto.UserWorkloadDto(
                u.publicId, u.name, u.email, COUNT(l))
            FROM User u
            LEFT JOIN Lead l
                   ON l.assignedUser = u
                  AND l.deletedAt IS NULL
            WHERE u.tenantId = :tenantId
              AND u.isActive = true
              AND u.deletedAt IS NULL
              AND u.id IN :userIds
            GROUP BY u.publicId, u.name, u.email
            ORDER BY COUNT(l) DESC, u.name ASC
            """)
    List<UserWorkloadDto> findUserWorkloadForUsers(@Param("tenantId") Long tenantId,
                                                   @Param("userIds") Collection<Long> userIds);

    /** Lead count per (user, stage) pair — feeds the per-user pipeline view. */
    @Query("""
            SELECT new com.crm.travelcrm.lead.dto.UserLeadStageCountDto(
                u.publicId, u.name, l.leadStage, COUNT(l))
            FROM Lead l
            JOIN l.assignedUser u
            WHERE l.tenantId = :tenantId
              AND l.deletedAt IS NULL
            GROUP BY u.publicId, u.name, l.leadStage
            ORDER BY u.name ASC, l.leadStage ASC
            """)
    List<UserLeadStageCountDto> countLeadsByStagePerUser(@Param("tenantId") Long tenantId);

    /**
     * Scope-filtered (user, stage) breakdown — same as {@link #countLeadsByStagePerUser} but
     * limited to the caller's own/team-visible owner ids.
     */
    @Query("""
            SELECT new com.crm.travelcrm.lead.dto.UserLeadStageCountDto(
                u.publicId, u.name, l.leadStage, COUNT(l))
            FROM Lead l
            JOIN l.assignedUser u
            WHERE l.tenantId = :tenantId
              AND l.deletedAt IS NULL
              AND u.id IN :userIds
            GROUP BY u.publicId, u.name, l.leadStage
            ORDER BY u.name ASC, l.leadStage ASC
            """)
    List<UserLeadStageCountDto> countLeadsByStagePerUserForUsers(@Param("tenantId") Long tenantId,
                                                                 @Param("userIds") Collection<Long> userIds);
}