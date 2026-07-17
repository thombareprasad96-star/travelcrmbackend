package com.crm.travelcrm.task.repository;

import com.crm.travelcrm.task.entity.Task;
import com.crm.travelcrm.task.enums.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskRepository
        extends JpaRepository<Task, Long>, JpaSpecificationExecutor<Task> {

    Optional<Task> findByPublicIdAndTenantIdAndDeletedAtIsNull(UUID publicId, Long tenantId);

    // ── My Tasks: everything assigned to me in the given (open) states ───────────────────────
    List<Task> findByTenantIdAndAssignToUserIdAndStatusInAndDeletedAtIsNull(
            Long tenantId, Long assignToUserId, Collection<TaskStatus> statuses);

    // ── Workload source: all live tasks except the given status (CANCELLED). logs are LAZY. ──
    List<Task> findByTenantIdAndStatusNotAndDeletedAtIsNull(Long tenantId, TaskStatus status);

    // ── Stats counters ───────────────────────────────────────────────────────────────────────
    long countByTenantIdAndDeletedAtIsNull(Long tenantId);

    long countByTenantIdAndStatusAndDeletedAtIsNull(Long tenantId, TaskStatus status);

    /** Open tasks whose calendar anchor (startAt, else dueDate) is already in the past. */
    @Query("""
            SELECT COUNT(t) FROM Task t
            WHERE t.tenantId = :tenantId AND t.deletedAt IS NULL
              AND t.status IN :openStatuses
              AND COALESCE(t.startAt, t.dueDate) < :now
            """)
    long countOverdue(@Param("tenantId") Long tenantId,
                      @Param("openStatuses") Collection<TaskStatus> openStatuses,
                      @Param("now") Instant now);

    /** Open tasks whose calendar anchor falls inside [start, end). */
    @Query("""
            SELECT COUNT(t) FROM Task t
            WHERE t.tenantId = :tenantId AND t.deletedAt IS NULL
              AND t.status IN :openStatuses
              AND COALESCE(t.startAt, t.dueDate) >= :start
              AND COALESCE(t.startAt, t.dueDate) < :end
            """)
    long countDueBetween(@Param("tenantId") Long tenantId,
                         @Param("openStatuses") Collection<TaskStatus> openStatuses,
                         @Param("start") Instant start,
                         @Param("end") Instant end);

    /**
     * Per-user task count split by status — a component of the shared workload metric
     * ({@code UserWorkload.score()}).
     *
     * <p>One tenant-scoped GROUP BY, so a pool of N users costs one query rather than N. Returns rows
     * ONLY for users that currently hold at least one task in {@code statuses}; the caller must
     * zero-fill, so a user with nothing to do is a genuine (and most-attractive) candidate rather
     * than an absent one. Each row is {@code [userId (Long), status (TaskStatus), count (Long)]}.
     */
    @Query("""
            SELECT t.assignToUserId, t.status, COUNT(t)
            FROM Task t
            WHERE t.tenantId = :tenantId
              AND t.deletedAt IS NULL
              AND t.assignToUserId IN :userIds
              AND t.status IN :statuses
            GROUP BY t.assignToUserId, t.status
            """)
    List<Object[]> countTasksPerUserByStatus(@Param("tenantId") Long tenantId,
                                             @Param("userIds") Collection<Long> userIds,
                                             @Param("statuses") Collection<TaskStatus> statuses);
}