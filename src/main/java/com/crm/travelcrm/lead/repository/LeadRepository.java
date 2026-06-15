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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LeadRepository extends JpaRepository<Lead, Long> {

    // ── List ─────────────────────────────────────────────────────────────────
    @EntityGraph(attributePaths = "assignedUser")
    Page<Lead> findAllByTenantIdAndDeletedAtIsNull(
            Long tenantId, Pageable pageable);

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
}