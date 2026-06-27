package com.crm.travelcrm.activity.repository;

import com.crm.travelcrm.activity.entity.ActivityLog;
import com.crm.travelcrm.activity.entity.ActivityAction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Reads over the audit trail for the Activity Reports.
 *
 * <p>Every query is tenant-scoped by an explicit {@code tenantId} param (belt-and-braces — the
 * {@code tenantFilter} only activates inside {@code @Transactional} methods) and excludes
 * soft-deleted rows. Optional filters use the {@code (:p IS NULL OR col = :p)} idiom so a single
 * query serves every filter combination.
 */
public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long> {

    /** Detail lookup by external id — owner-scoped to the tenant (foreign publicId ⇒ empty ⇒ 404). */
    Optional<ActivityLog> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    @Query("""
            SELECT a FROM ActivityLog a
            WHERE a.tenantId = :tenantId
              AND a.deletedAt IS NULL
              AND a.createdAt BETWEEN :from AND :to
              AND (:action   IS NULL OR a.action       = :action)
              AND (:userType IS NULL OR a.userType     = :userType)
              AND (:userId   IS NULL OR a.actingUserId = :userId)
            ORDER BY a.createdAt DESC
            """)
    Page<ActivityLog> findWithFilters(
            @Param("tenantId") Long tenantId,
            @Param("from")     LocalDateTime from,
            @Param("to")       LocalDateTime to,
            @Param("action")   ActivityAction action,
            @Param("userType") String userType,
            @Param("userId")   Long userId,
            Pageable pageable);

    @Query("""
            SELECT a FROM ActivityLog a
            WHERE a.tenantId = :tenantId
              AND a.deletedAt IS NULL
              AND a.createdAt BETWEEN :from AND :to
              AND (:action   IS NULL OR a.action       = :action)
              AND (:userType IS NULL OR a.userType     = :userType)
              AND (:userId   IS NULL OR a.actingUserId = :userId)
            ORDER BY a.createdAt DESC
            """)
    List<ActivityLog> findAllWithFilters(
            @Param("tenantId") Long tenantId,
            @Param("from")     LocalDateTime from,
            @Param("to")       LocalDateTime to,
            @Param("action")   ActivityAction action,
            @Param("userType") String userType,
            @Param("userId")   Long userId);

    long countByTenantIdAndCreatedAtBetweenAndDeletedAtIsNull(
            Long tenantId, LocalDateTime from, LocalDateTime to);

    long countByTenantIdAndActionAndCreatedAtBetweenAndDeletedAtIsNull(
            Long tenantId, ActivityAction action, LocalDateTime from, LocalDateTime to);

    long countByTenantIdAndUserTypeAndCreatedAtBetweenAndDeletedAtIsNull(
            Long tenantId, String userType, LocalDateTime from, LocalDateTime to);

    @Query("""
            SELECT COUNT(DISTINCT a.actingUserId) FROM ActivityLog a
            WHERE a.tenantId = :tenantId
              AND a.deletedAt IS NULL
              AND a.createdAt BETWEEN :from AND :to
            """)
    long countDistinctUsers(
            @Param("tenantId") Long tenantId,
            @Param("from")     LocalDateTime from,
            @Param("to")       LocalDateTime to);
}